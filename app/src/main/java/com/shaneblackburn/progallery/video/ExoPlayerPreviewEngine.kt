package com.shaneblackburn.progallery.video

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PROTOTYPE preview engine — a spike to evaluate replacing CompositionPlayer for the LIVE preview.
 * **Not wired into the production editor** (PreviewController/VideoEditorScreen are untouched); it is
 * exercised only by the spike instrumented test and the debug-only spike screen.
 *
 * Approach: play the project's clips as a plain [ExoPlayer] **playlist** (clips run sequentially, so a
 * single video decoder is used at a time — no CompositionPlayer prewarming / concurrent-decoder trap),
 * with video decoding preferring **software** ([MediaCodecSelector.PREFER_SOFTWARE]) for cross-device
 * reliability, and the project's REAL per-clip effects applied through [ExoPlayer.setVideoEffects]:
 * colour grade, filter, geometry and the existing per-clip transition ([ClipTransitionEffect]) — the
 * same shaders as export. The effect chain is swapped at each clip boundary ([onMediaItemTransition]).
 *
 * This faithfully reproduces ProGallery's CURRENT transitions, which are per-clip "through-a-state"
 * effects (fade/blur/zoom/wipe/push to or from a neutral state) — it does NOT do true cross-clip
 * blending (the app doesn't either; that's a separate future feature). Export stays on Transformer.
 *
 * Known prototype limitations (see the spike report): background music (a parallel audio track) is not
 * mixed by a single playlist player; per-clip speed is approximated via [PlaybackParameters] rather than
 * the export's frame-timestamp SpeedChangeEffect; per-clip volume is applied via the player volume on
 * each boundary. All of these remain fully correct on export.
 */
@OptIn(UnstableApi::class)
class ExoPlayerPreviewEngine(
    private val context: Context,
    private val preferSoftware: Boolean = true,
    private val onDecoderName: (String) -> Unit = {},
    private val onError: (PlaybackException) -> Unit = {},
) {
    var player: ExoPlayer? = null
        private set

    /** The actual video decoder Media3 selected (e.g. "c2.android.avc.decoder" = software), once known. */
    @Volatile
    var videoDecoderName: String? = null
        private set

    private var project: VideoProject? = null
    private var outW = 0
    private var outH = 0

    @MainThread
    fun load(project: VideoProject, startMs: Long = 0L, withEffects: Boolean = true) {
        release()
        if (project.clips.isEmpty()) return
        this.project = project
        val (w, h) = previewSize(project, PREVIEW_MAX_PX)
        outW = w; outH = h

        val renderers = DefaultRenderersFactory(context).apply {
            if (preferSoftware) setMediaCodecSelector(MediaCodecSelector.PREFER_SOFTWARE)
        }
        val p = ExoPlayer.Builder(context, renderers).build()
        p.setSeekParameters(SeekParameters.EXACT)
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoderName = decoderName
                onDecoderName(decoderName)
            }
        })
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (withEffects) applyClipEffects(p.currentMediaItemIndex)
                applyClipAudio(p.currentMediaItemIndex)
            }
            override fun onPlayerError(error: PlaybackException) = onError(error)
        })

        project.clips.forEach { p.addMediaItem(mediaItemFor(it)) }
        // setVideoEffects must be called at least once before prepare() to set up the pipeline.
        if (withEffects) p.setVideoEffects(ProjectComposer.playbackVideoEffects(project, 0, outW, outH))
        player = p
        applyClipAudio(0)
        p.prepare()
        val (index, offset) = project.simpleSeek(startMs)
        p.seekTo(index, offset)
    }

    /** Swap the per-clip video effect chain to the clip now playing (colour, geometry, transition). */
    private fun applyClipEffects(index: Int) {
        val proj = project ?: return
        val p = player ?: return
        val i = index.coerceIn(0, proj.clips.lastIndex)
        p.setVideoEffects(ProjectComposer.playbackVideoEffects(proj, i, outW, outH))
    }

    private fun applyClipAudio(index: Int) {
        val proj = project ?: return
        val p = player ?: return
        val clip = proj.clips.getOrNull(index.coerceIn(0, proj.clips.lastIndex)) ?: return
        p.volume = if (clip.muted) 0f else clip.volume.coerceIn(0f, 1f)
        // Per-clip speed via playback parameters (ExoPlayer pitch-corrects audio). Approximation of the
        // export's SpeedChangeEffect; documented as a prototype limitation.
        p.playbackParameters = PlaybackParameters(clip.speed.coerceAtLeast(0.1f))
    }

    private fun mediaItemFor(clip: Clip): MediaItem =
        if (clip.isImage) {
            MediaItem.Builder()
                .setUri(Uri.parse(clip.uri))
                .setMimeType(clip.mimeType ?: MimeTypes.IMAGE_JPEG)
                .setImageDurationMs(clip.timelineDurationMs)
                .build()
        } else {
            ProjectComposer.clipMediaItem(clip)
        }

    /** Whole-timeline position (ms), accounting for earlier clips and clip speed. */
    fun timelinePositionMs(): Long {
        val p = player ?: return 0L
        val proj = project ?: return 0L
        return proj.simpleTimelinePosition(p.currentMediaItemIndex, p.currentPosition)
    }

    @MainThread fun play() { player?.play() }
    @MainThread fun pause() { player?.pause() }
    @MainThread fun seekToTimeline(ms: Long) {
        val p = player ?: return
        val proj = project ?: return
        val (index, offset) = proj.simpleSeek(ms)
        p.seekTo(index, offset)
    }

    @MainThread fun attach(view: PlayerView?) { view?.player = player }
    @MainThread fun setSurface(surface: Surface?) { player?.setVideoSurface(surface) }

    private fun previewSize(project: VideoProject, maxShortSidePx: Int): Pair<Int, Int> {
        val (w, h) = ProjectComposer.outputSize(project)
        val shortSide = min(w, h)
        if (maxShortSidePx <= 0 || shortSide <= maxShortSidePx) return even(w) to even(h)
        val s = maxShortSidePx.toFloat() / shortSide
        return even((w * s).roundToInt()) to even((h * s).roundToInt())
    }

    private fun even(v: Int) = max(2, v - (v % 2))

    @MainThread
    fun release() {
        player?.release()
        player = null
        videoDecoderName = null
    }

    private companion object {
        const val PREVIEW_MAX_PX = 720
    }
}
