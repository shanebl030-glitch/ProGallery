package com.shaneblackburn.progallery.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.ClipEdge
import com.shaneblackburn.progallery.core.video.Compat
import com.shaneblackburn.progallery.core.video.VideoGrade
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlin.math.roundToInt

/** Output resolution choices (short side), 0 = keep the project's natural size. */
enum class ExportResolution(val label: String, val shortSide: Int) {
    ORIGINAL("Original", 0), UHD("4K", 2160), FHD("1080p", 1080), HD("720p", 720), SD("480p", 480),
}

enum class ExportQuality(val label: String, val bitsPerPixel: Float) {
    HIGH("High", 0.16f), STANDARD("Standard", 0.10f), SMALL("Small file", 0.06f),
}

enum class ExportCodec(val label: String, val mime: String) {
    H264("H.264 (most compatible)", androidx.media3.common.MimeTypes.VIDEO_H264),
    HEVC("HEVC (smaller)", androidx.media3.common.MimeTypes.VIDEO_H265),
}

data class VideoExportSettings(
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    /** 0 = keep source frame rate. Only reduces (frames are dropped, never invented). */
    val frameRate: Int = 0,
    val quality: ExportQuality = ExportQuality.STANDARD,
    val codec: ExportCodec = ExportCodec.H264,
)

/**
 * Builds a Media3 [Composition] from a [VideoProject]. Used for both the live preview
 * (CompositionPlayer) and export (Transformer), so the two always match.
 */
@OptIn(UnstableApi::class)
object ProjectComposer {

    /** Output frame size (even numbers, as encoders require). */
    fun outputSize(project: VideoProject, settings: VideoExportSettings = VideoExportSettings()): Pair<Int, Int> {
        val first = project.clips.firstOrNull() ?: return 1280 to 720
        var w = first.orientedWidth.coerceAtLeast(2)
        var h = first.orientedHeight.coerceAtLeast(2)
        project.aspect.ratio?.let { r ->
            // Fit the requested aspect inside the first clip's frame.
            if (w.toFloat() / h > r) w = (h * r).roundToInt() else h = (w / r).roundToInt()
        }
        val target = settings.resolution.shortSide
        if (target > 0) {
            val short = minOf(w, h)
            if (target < short) { val s = target.toFloat() / short; w = (w * s).roundToInt(); h = (h * s).roundToInt() }
        }
        // Clamp to even dimensions within a GPU-safe maximum (compatibility across devices).
        return Compat.clampOutput(w, h)
    }

    fun build(project: VideoProject, settings: VideoExportSettings = VideoExportSettings()): Composition {
        require(project.clips.isNotEmpty()) { "Add at least one clip" }
        val (outW, outH) = outputSize(project, settings)

        val videoItems = project.clips.mapIndexed { index, clip -> editedItem(project, index, clip, outW, outH, settings) }
        // Media3 1.11 requires each sequence's track types to be declared explicitly: the list
        // constructor leaves TRACK_TYPE_NONE, and the old Composition.experimentalSetForceAudioTrack
        // (now deprecated) no longer resolves it, so the composition is rejected with "trackTypes
        // must only contain TRACK_TYPE_AUDIO and/or TRACK_TYPE_VIDEO" in BOTH export and preview.
        // Declaring AUDIO+VIDEO here keeps the continuous audio track for clips/images without audio.
        val sequences = mutableListOf(
            EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
                .addItems(videoItems)
                .build(),
        )

        project.music?.let { music ->
            val total = project.durationMs
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(music.uri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(music.startOffsetMs.coerceAtLeast(0)).build(),
                )
                .build()
            val musicItem = EditedMediaItem.Builder(mediaItem)
                // CompositionPlayer (preview) requires the media's full duration.
                .setDurationUs(music.durationMs * 1000)
                .setRemoveVideo(true)
                .setEffects(Effects(listOf<AudioProcessor>(GainEnvelopeProcessor(music.volume, music.fadeInMs, music.fadeOutMs, total)), emptyList()))
                .build()
            sequences += EditedMediaItemSequence.Builder(ImmutableSet.of(C.TRACK_TYPE_AUDIO))
                .addItems(listOf(musicItem))
                .setIsLooping(music.loop)
                .build()
        }

        // Fades and transitions are applied per clip (see ClipTransitionEffect), not here,
        // because a composition effect can't tell where a clip boundary falls on the timeline.
        val compositionVideoEffects = buildList<Effect> {
            if (project.texts.isNotEmpty()) add(OverlayEffect(ImmutableList.of<TextureOverlay>(TimedTextOverlay(project, outW, outH))))
            if (settings.frameRate > 0) add(FrameDropEffect.createDefaultFrameDropEffect(settings.frameRate.toFloat()))
        }

        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), compositionVideoEffects))
            // (Continuous audio track is now ensured by the video sequence's AUDIO track type above,
            // replacing the deprecated experimentalSetForceAudioTrack.)
            // Tone-map HDR (and 10-bit) input down to SDR. Phones (e.g. Samsung) record
            // HDR10+/HEVC-10bit by default; the GL editing pipeline and our SDR grade shader
            // can't consume BT.2020/10-bit, which otherwise fails with ERROR_CODE_DECODING_FAILED
            // in both the preview and export. OpenGL tone-mapping is supported from API 29
            // (our minSdk) and is a pass-through for SDR content.
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
    }

    private fun editedItem(project: VideoProject, index: Int, clip: Clip, outW: Int, outH: Int, settings: VideoExportSettings): EditedMediaItem {
        val edge = project.clipEdge(index)
        return if (clip.isImage) imageItem(clip, outW, outH, edge) else videoItem(clip, outW, outH, edge)
    }

    /** The per-clip transition/fade effect, appended last so it sees the clip's 0-based output time. */
    private fun transitionEffect(edge: ClipEdge): Effect? = if (ClipTransitionEffect.needed(edge)) ClipTransitionEffect(edge) else null

    /** Colour effects shared by photo and video clips (grade, then filter look). */
    private fun colorEffects(clip: Clip): List<Effect> = buildList {
        val grade = VideoGrade.from(clip.color)
        if (!grade.isIdentity) add(GradeEffect(grade))
        if (ClipColorMatrix.needed(clip)) add(ClipColorMatrix(clip))
    }

    /**
     * User-applied flips and quarter turns. [extraCwDegrees] adds clockwise rotation
     * baked into the source (photos' EXIF orientation; videos get theirs from the
     * container automatically, so pass 0).
     */
    private fun geometryEffect(clip: Clip, extraCwDegrees: Int = 0): Effect? {
        val cw = (90 * clip.quarterTurns + extraCwDegrees) % 360
        if (cw == 0 && !clip.flipH && !clip.flipV) return null
        return ScaleAndRotateTransformation.Builder()
            .setScale(if (clip.flipH) -1f else 1f, if (clip.flipV) -1f else 1f)
            // Media3 rotates counter-clockwise for positive degrees.
            .setRotationDegrees(-cw.toFloat())
            .build()
    }

    /** A still photo shown for the clip's duration. Media3 does not apply EXIF, so we rotate it here. */
    private fun imageItem(clip: Clip, outW: Int, outH: Int, edge: ClipEdge): EditedMediaItem {
        val durationMs = clip.timelineDurationMs
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.uri))
            .setMimeType(clip.mimeType ?: MimeTypes.IMAGE_JPEG)
            .setImageDurationMs(durationMs)
            .build()
        val video = buildList<Effect> {
            geometryEffect(clip, extraCwDegrees = clip.sourceRotation)?.let(::add)
            addAll(colorEffects(clip))
            add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            transitionEffect(edge)?.let(::add)
        }
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(durationMs * 1000)
            .setFrameRate(IMAGE_FPS)
            .setEffects(Effects(emptyList(), video))
            .build()
    }

    /** The trimmed [MediaItem] for a video [clip]. Shared by export and the composed-frame preview so
     * both decode the exact same source range (and Media3's FrameExtractor can reuse its decoder while
     * the clip is unchanged, since equal MediaItems avoid a re-prepare). */
    fun clipMediaItem(clip: Clip): MediaItem = MediaItem.Builder()
        .setUri(Uri.parse(clip.uri))
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.trimStartMs)
                .setEndPositionMs(clip.trimEndMs)
                .build(),
        )
        .build()

    /**
     * The per-clip VIDEO effect chain for the on-demand composed preview (Media3 FrameExtractor),
     * built from the SAME helpers as export so the previewed frame is produced by the same shaders.
     * Differs from export only in ways that don't change a still frame's pixels: the speed effect is
     * omitted (speed changes playback rate, not the frame at a given position — the caller seeks to the
     * exact source frame), and the transition is evaluated at [transitionTimeMs] (the known playhead
     * output-time) instead of inferring time from the single extracted frame. [outW]x[outH] is the
     * preview render size (typically a downscaled copy of the export size, same aspect/letterboxing).
     */
    fun previewVideoEffects(
        project: VideoProject,
        index: Int,
        outW: Int,
        outH: Int,
        transitionTimeMs: () -> Long,
    ): List<Effect> {
        val clip = project.clips[index]
        val edge = project.clipEdge(index)
        return buildList {
            geometryEffect(clip)?.let(::add)
            addAll(colorEffects(clip))
            add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            if (ClipTransitionEffect.needed(edge)) add(ClipTransitionEffect(edge, fixedTimeMs = transitionTimeMs))
        }
    }

    /**
     * Per-clip VIDEO effect chain for the PROTOTYPE ExoPlayer preview engine (continuous playback via
     * ExoPlayer.setVideoEffects). Same helpers as export, so the look matches, with two necessary
     * differences for a live playlist player: the speed effect is omitted (ExoPlayer.setVideoEffects
     * forbids timestamp-altering effects — playback speed is applied via PlaybackParameters instead),
     * and the transition uses its default frame-relative timing (NOT a fixed-time provider), because a
     * continuously-playing clip's frames carry the clip's 0-based output time, which is exactly what
     * [ClipTransitionEffect] expects. Additive: does not change export or the existing preview.
     */
    fun playbackVideoEffects(project: VideoProject, index: Int, outW: Int, outH: Int): List<Effect> {
        val clip = project.clips[index]
        val edge = project.clipEdge(index)
        return buildList {
            geometryEffect(clip)?.let(::add)
            addAll(colorEffects(clip))
            add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            if (ClipTransitionEffect.needed(edge)) add(ClipTransitionEffect(edge))
        }
    }

    private fun videoItem(clip: Clip, outW: Int, outH: Int, edge: ClipEdge): EditedMediaItem {
        val mediaItem = clipMediaItem(clip)

        val video = buildList<Effect> {
            if (clip.speed != 1f) add(SpeedChangeEffect(clip.speed))
            geometryEffect(clip)?.let(::add)
            addAll(colorEffects(clip))
            // Every clip is fitted to the same output frame (letterboxed if needed).
            add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
            transitionEffect(edge)?.let(::add)
        }

        val audio = buildList<AudioProcessor> {
            if (clip.speed != 1f) add(SonicAudioProcessor().apply { setSpeed(clip.speed); setPitch(1f) })
            val gain = if (clip.muted) 0f else clip.volume
            if (gain != 1f) add(volumeProcessor(gain))
        }

        return EditedMediaItem.Builder(mediaItem)
            // Full source duration (before clipping); required by CompositionPlayer for preview.
            .setDurationUs(clip.sourceDurationMs * 1000)
            .setEffects(Effects(audio, video))
            .build()
    }

    /** Channel-count-agnostic volume via an identity mixing matrix scaled by [gain]. */
    private fun volumeProcessor(gain: Float): AudioProcessor = ChannelMixingAudioProcessor().apply {
        // Media3 1.11 removed ChannelMixingMatrix.create(in,out); build the per-channel identity
        // explicitly (createForConstantGain only supports 1–2 channels, this keeps 1..8 working).
        for (ch in 1..8) {
            val coeffs = FloatArray(ch * ch)
            for (i in 0 until ch) coeffs[i * ch + i] = 1f
            putChannelMixingMatrix(ChannelMixingMatrix(ch, ch, coeffs).scaleBy(gain))
        }
    }

    /** Frame rate photos are rendered at (matches typical phone video). */
    const val IMAGE_FPS = 30

    /** Suggested video bitrate for a frame size, frame rate and quality. */
    fun bitrate(w: Int, h: Int, fps: Int, quality: ExportQuality, codec: ExportCodec): Int {
        val f = if (fps > 0) fps else 30
        val base = w.toLong() * h * f * quality.bitsPerPixel * (if (codec == ExportCodec.HEVC) 0.65f else 1f)
        return base.toLong().coerceIn(1_000_000L, 120_000_000L).toInt()
    }
}
