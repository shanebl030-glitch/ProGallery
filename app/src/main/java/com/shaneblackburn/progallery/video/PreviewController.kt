package com.shaneblackburn.progallery.video

import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.ui.PlayerView
import com.shaneblackburn.progallery.CrashReporter
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.VideoProject
import java.lang.reflect.Method

/** Which preview engine is currently showing. */
enum class PreviewMode { FULL, SIMPLIFIED }

/**
 * Owns the video editor's live preview, with graceful fallback.
 *
 * Primary engine: [CompositionPlayer], which renders the exact export — colour grade,
 * filters, transitions, titles, speed. A Media3 CompositionPlayer accepts only one
 * composition in its lifetime, so every project change builds a fresh one.
 *
 * Fallback engine: if the composition pipeline can't decode a clip (some devices reject
 * exotic codecs/resolutions even after HDR tone-mapping), the preview switches to a plain
 * [ExoPlayer] that plays the trimmed clips back to back with no effects. ExoPlayer decodes
 * HDR and 10-bit natively, so the footage is still visible and scrubbable; colour, speed
 * and effects are then only seen on export. The UI shows a banner in this mode.
 *
 * All methods must be called on the main thread.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class PreviewController(
    private val context: Context,
    private val onPlayingChanged: (Boolean) -> Unit = {},
    /** null clears any error; non-null is a user-facing message. */
    private val onError: (String?) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onMode: (PreviewMode) -> Unit = {},
    /** Full technical reason the full-effects preview fell back (for diagnostics), or null. */
    private val onFallbackReason: (String?) -> Unit = {},
    /** When true, skip the GPU effects pipeline entirely and use the simple player (Safe mode). */
    private val safeMode: () -> Boolean = { false },
) {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var player: Player? = null
    private var view: PlayerView? = null
    private var current: VideoProject? = null
    var mode: PreviewMode = PreviewMode.FULL
        private set

    // --- Diagnostic cross-device workaround (preview only) --------------------------------------
    // CompositionPlayer enables video "prewarming" by default: it opens a SECOND concurrent hardware
    // video decoder for the next clip. Some devices provide only one decoder instance for a codec, so
    // a multi-clip composition (e.g. after a cut) fails to decode the second clip
    // (ERROR_CODE_DECODING_FAILED) and the full-effects preview falls back to simplified. Media3
    // exposes no public way to disable prewarming, so once a decode failure proves this
    // device/composition can't run the concurrent decoders, we rebuild ONCE with prewarming disabled
    // (single decoder, reused across clips) and keep it off for the rest of the session. This both
    // works around the limit AND tests whether concurrent decoding was really the cause.
    private var videoPrewarmingDisabled = false
    // Diagnostic surfaced in the fallback "Details" text so the prewarming-retry outcome is visible
    // on the phone WITHOUT logcat. Reflects the attempt that actually fell back:
    //   NO-API             reflection unavailable in this Media3 build (no retry possible)
    //   ON(default)        first attempt, prewarming on (default) — a retry should follow if it was a decode error
    //   OFF(retry)         retry attempt, prewarming successfully disabled (single decoder)
    //   ON(disable-failed) retry attempt, but the reflection invoke failed so prewarming stayed on
    // If the banner still shows after a cut with "OFF(retry)", a single decoder also failed, which
    // REFUTES the concurrent-decoder hypothesis (cause lies elsewhere). "ON(default)" means the retry
    // never fired; "NO-API"/"ON(disable-failed)" mean the reflection didn't take effect.
    private var prewarmDiag = "prewarm=untested"
    // Reflected package-private CompositionPlayer.Builder.setVideoPrewarmingEnabled(boolean) — there
    // is no public API. Resolved once; null means this Media3 build doesn't expose it (then we never
    // retry, and behaviour is exactly the existing fallback). See also the -keep ProGuard rule.
    private val prewarmingSetter: Method? by lazy {
        runCatching {
            CompositionPlayer.Builder::class.java
                .getDeclaredMethod("setVideoPrewarmingEnabled", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull().also {
            android.util.Log.i("PreviewController", "CompositionPlayer prewarming setter " +
                if (it != null) "resolved" else "NOT found in this Media3 build (prewarming cannot be disabled)")
        }
    }

    val isPlaying: Boolean get() = player?.isPlaying == true

    /**
     * The whole-timeline playback position. In FULL mode the CompositionPlayer plays one composition
     * whose position already is the timeline position. In SIMPLIFIED mode the ExoPlayer plays a
     * playlist, so player.currentPosition is only the position WITHIN the current clip and ignores
     * speed — [VideoProject.simpleTimelinePosition] adds the earlier clips' durations and the speed
     * factor so the playhead/timecode track the real timeline across clip boundaries during playback.
     */
    val currentPositionMs: Long
        get() {
            val p = player ?: return 0L
            val proj = current
            return if (mode == PreviewMode.SIMPLIFIED && proj != null)
                proj.simpleTimelinePosition(p.currentMediaItemIndex, p.currentPosition)
            else p.currentPosition
        }

    @MainThread
    fun attach(playerView: PlayerView?) {
        view = playerView
        playerView?.player = player
    }

    /** Shows [project] starting at [positionMs]. Returns false only if nothing could be shown. */
    @MainThread
    fun show(project: VideoProject, positionMs: Long): Boolean {
        val wasPlaying = isPlaying
        releasePlayer()
        current = project
        if (project.clips.isEmpty()) return false
        val target = positionMs.coerceIn(0, maxOf(0, project.durationMs - 1))
        // Safe mode: deliberately skip the CompositionPlayer GPU pipeline (the most likely cause
        // of a device-specific native crash) and preview with the plain player instead.
        if (safeMode()) {
            CrashReporter.setPhase("video:preview-safe")
            return startSimplePlayer(project, target, wasPlaying, cause = null)
        }
        mode = PreviewMode.FULL
        onMode(PreviewMode.FULL)
        CrashReporter.setPhase("video:preview-full")
        return try {
            // Replayable cache lets experimentalRedrawLastFrame() force an accurate effect redraw
            // after a seek (needed so the transition effect clears when scrubbing out of it, and so
            // a scrub lands on the right frame). It costs extra power/resources, but only in the
            // live preview, never the export.
            val builder = CompositionPlayer.Builder(context).experimentalSetEnableReplayableCache(true)
            // On a device proven unable to run the concurrent decoders prewarming needs, build with
            // prewarming disabled (single decoder). Safe: if the reflection isn't available this is a
            // no-op and prewarming stays on.
            val prewarmingDisabledNow = if (videoPrewarmingDisabled) applyPrewarmingDisabled(builder) else false
            prewarmDiag = "prewarm=" + when {
                prewarmingSetter == null -> "NO-API"
                !videoPrewarmingDisabled -> "ON(default)"
                prewarmingDisabledNow -> "OFF(retry)"
                else -> "ON(disable-failed)"
            }
            val p = builder.build()
            var pendingFrameSeek = !wasPlaying
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = onPlayingChanged(isPlaying)
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        if (pendingFrameSeek) { pendingFrameSeek = false; p.seekTo(target) }
                        onReady()
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    // Don't release the CompositionPlayer from inside its own error callback
                    // (re-entrant release throws IllegalStateException). Defer to the next tick.
                    mainHandler.post {
                        if (player !== p) return@post
                        // One-shot diagnostic retry: a decode failure while prewarming is on is
                        // consistent with the device being unable to run the two concurrent hardware
                        // decoders prewarming needs. Rebuild ONCE with prewarming disabled (single
                        // decoder). If that also fails, keep the safe simplified fallback. The guard
                        // videoPrewarmingDisabled makes this strictly one retry (no loop/repeat).
                        if (!videoPrewarmingDisabled && prewarmingSetter != null && isDecodeError(error)) {
                            videoPrewarmingDisabled = true
                            android.util.Log.w("PreviewController",
                                "full-effects preview decode error ${error.errorCodeName}; retrying ONCE with video prewarming disabled", error)
                            show(project, target)
                        } else {
                            fallbackToSimplePlayer(project, target, wasPlaying, error)
                        }
                    }
                }
            })
            player = p
            view?.player = p
            p.setComposition(ProjectComposer.build(project))
            p.prepare()
            p.seekTo(target)
            if (wasPlaying) p.play()
            onError(null)
            onFallbackReason(null)
            true
        } catch (e: Exception) {
            // Building the composition itself failed; try raw playback.
            fallbackToSimplePlayer(project, target, wasPlaying, e)
        }
    }

    /** Backwards-compatible name for an error-driven fallback. */
    @MainThread
    private fun fallbackToSimplePlayer(project: VideoProject, target: Long, wasPlaying: Boolean, cause: Throwable): Boolean =
        startSimplePlayer(project, target, wasPlaying, cause)

    /**
     * Raw ExoPlayer playback of the clips (no effects). Used either because the composition
     * pipeline couldn't decode the media ([cause] non-null) or because Safe preview mode is on
     * ([cause] null, a deliberate choice). Returns true if it started.
     */
    @MainThread
    private fun startSimplePlayer(project: VideoProject, target: Long, wasPlaying: Boolean, cause: Throwable?): Boolean {
        releasePlayer()
        if (cause != null) {
            // Record exactly why the effects pipeline failed, so a device-specific issue can be
            // diagnosed from the app (and logcat) without needing the source video.
            val reason = buildReason(project, cause)
            android.util.Log.e("PreviewController", "effects preview failed; using simplified playback. $reason", cause)
            onFallbackReason(reason)
        } else {
            onFallbackReason(null)
        }
        if (project.clips.isEmpty()) { onError(cause?.let { diagnose(project, it) }); return false }
        return try {
            val exo = ExoPlayer.Builder(context).build()
            // Frame-accurate seeking so scrubbing and frame-step land on the exact frame the
            // playhead points at (the default snaps to the nearest sync sample).
            exo.setSeekParameters(SeekParameters.EXACT)
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) = onPlayingChanged(isPlaying)
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) onReady() }
                override fun onPlayerError(error: PlaybackException) {
                    onError("Can't preview this video on this device (${error.errorCodeName}). It will still be included in the export.")
                }
            })
            project.clips.forEach { clip -> exo.addMediaItem(simpleMediaItem(clip)) }
            player = exo
            view?.player = exo
            mode = PreviewMode.SIMPLIFIED
            onMode(PreviewMode.SIMPLIFIED)
            exo.prepare()
            // Map the timeline position to a playlist (index, offset); speed changes aren't
            // applied here, so this is approximate for sped-up/slowed clips.
            val (index, offset) = locateInSimpleTimeline(project, target)
            exo.seekTo(index, offset)
            if (wasPlaying) exo.play()
            onError(simplifiedNotice(project, cause))
            true
        } catch (e: Exception) {
            onError(diagnose(project, e))
            false
        }
    }

    private fun simpleMediaItem(clip: Clip): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(clip.uri))
        if (clip.isImage) {
            b.setImageDurationMs(clip.timelineDurationMs)
        } else {
            b.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build(),
            )
        }
        return b.build()
    }

    /**
     * In the simplified (Safe-mode) timeline, which clip and in-item player offset [timelineMs]
     * lands in. Delegates to the unit-tested [VideoProject.simpleSeek], which accumulates clip
     * durations in TIMELINE time and scales the in-clip offset by the clip's speed — so scrubbing a
     * sped/slowed clip, or a later clip, lands on the right source frame (the old local version used
     * raw source durations and ignored speed).
     */
    private fun locateInSimpleTimeline(project: VideoProject, timelineMs: Long): Pair<Int, Long> =
        project.simpleSeek(timelineMs)

    private fun simplifiedNotice(project: VideoProject, cause: Throwable?): String {
        if (cause == null) return "Safe preview mode — colour, speed and effects still apply on export."
        val info = firstVideoInfo(project)
        val fmt = info?.summary()?.let { " ($it)" } ?: ""
        return "Simplified preview$fmt — colour, speed and effects apply on export."
    }

    /** A detailed technical reason string: error code, message, cause chain and clip format. */
    private fun buildReason(project: VideoProject, cause: Throwable): String {
        val sb = StringBuilder()
        val pe = cause as? PlaybackException
        if (pe != null) sb.append(pe.errorCodeName)
        val msg = cause.message
        if (!msg.isNullOrBlank()) sb.append(if (sb.isEmpty()) "" else ": ").append(msg)
        var c = cause.cause
        var depth = 0
        while (c != null && depth < 3) {
            c.message?.takeIf { it.isNotBlank() }?.let { sb.append(" ← ").append(c!!.javaClass.simpleName).append(": ").append(it) }
            c = c.cause; depth++
        }
        firstVideoInfo(project)?.let { sb.append(" [").append(it.summary()).append("]") }
        sb.append(" [clips=").append(project.clips.size).append(", ").append(prewarmDiag).append("]")
        return sb.toString().ifBlank { cause.javaClass.simpleName }
    }

    private fun diagnose(project: VideoProject, cause: Throwable): String {
        val info = firstVideoInfo(project)
        val reason = (cause as? PlaybackException)?.errorCodeName ?: cause.message ?: cause.javaClass.simpleName
        return if (info != null) "Preview unavailable for ${info.summary()} ($reason)."
        else "Preview unavailable ($reason)."
    }

    private fun firstVideoInfo(project: VideoProject): VideoInfo? =
        project.clips.firstOrNull { !it.isImage }?.let { VideoDiagnostics.read(context, Uri.parse(it.uri)) }

    @MainThread fun play() { player?.play() }
    @MainThread fun pause() { player?.pause() }
    @MainThread fun seekTo(ms: Long) {
        val p = player ?: return
        if (mode == PreviewMode.SIMPLIFIED) {
            current?.let { val (i, off) = locateInSimpleTimeline(it, ms); p.seekTo(i, off); return }
        }
        p.seekTo(ms)
        // Full-effects player: force the effect pipeline to redraw so the frame — and any
        // transition effect — matches the new position instead of a stale/stuck one (needs the
        // replayable cache the player is built with). The simplified ExoPlayer repaints seeks itself.
        redrawEffects()
    }

    /** A user seek (scrub step, frame-step, explicit). Same as [seekTo] now that seekTo redraws. */
    @MainThread fun scrubTo(ms: Long) = seekTo(ms)

    /**
     * Begin a timeline scrub gesture. Pauses first so that leaving scrubbing mode can never trigger
     * CompositionPlayer's implicit "resume if it was playing" (the preview must stay put on release).
     * In full mode it then enters the player's scrubbing mode, which is optimised for many frequent
     * seeks and suppresses playback output while active.
     */
    @MainThread fun beginScrub() {
        val p = player ?: return
        p.pause()
        (p as? CompositionPlayer)?.runCatching { setScrubbingModeEnabled(true) }
    }

    /** End a scrub gesture: land exactly on [ms] (redraws via seekTo) and leave scrubbing mode (the
     * player stays paused because beginScrub paused it). */
    @MainThread fun endScrub(ms: Long) {
        (player as? CompositionPlayer)?.runCatching { setScrubbingModeEnabled(false) }
        seekTo(ms)
    }

    /** Force the full-effects pipeline to redraw the current frame (needs the replayable cache the
     * player is built with). No-op for the simplified ExoPlayer, which repaints seeks itself. */
    private fun redrawEffects() {
        (player as? CompositionPlayer)?.let { runCatching { it.experimentalRedrawLastFrame() } }
    }

    private fun releasePlayer() {
        view?.player = null
        // CompositionPlayer.release() can throw (checkState) if it failed to prepare; never
        // let that crash the editor.
        runCatching { player?.release() }.onFailure { android.util.Log.w("PreviewController", "release threw", it) }
        player = null
    }

    @MainThread
    fun release() {
        releasePlayer()
        current = null
    }

    /** Disable CompositionPlayer video prewarming (single decoder) on [builder] via reflection.
     * Returns whether it was applied; any failure is logged and leaves prewarming on (safe). */
    private fun applyPrewarmingDisabled(builder: CompositionPlayer.Builder): Boolean {
        val m = prewarmingSetter ?: return false
        return runCatching { m.invoke(builder, false); true }
            .onSuccess { android.util.Log.i("PreviewController", "video prewarming DISABLED for the preview (single decoder)") }
            .onFailure { android.util.Log.w("PreviewController", "failed to disable video prewarming via reflection", it) }
            .getOrDefault(false)
    }

    private fun isDecodeError(e: PlaybackException): Boolean = e.errorCode in DECODE_RETRY_CODES

    private companion object {
        // Decode failures worth one retry on a single decoder (init / query / generic decode /
        // resource exhaustion). Excludes FORMAT_UNSUPPORTED and EXCEEDS_CAPABILITIES, where using
        // fewer decoders can't help.
        val DECODE_RETRY_CODES = intArrayOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        )
    }
}
