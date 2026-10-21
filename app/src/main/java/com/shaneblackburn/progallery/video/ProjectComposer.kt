package com.shaneblackburn.progallery.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
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
import com.shaneblackburn.progallery.core.video.Clip
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
        return (w / 2 * 2).coerceAtLeast(2) to (h / 2 * 2).coerceAtLeast(2)
    }

    fun build(project: VideoProject, settings: VideoExportSettings = VideoExportSettings()): Composition {
        require(project.clips.isNotEmpty()) { "Add at least one clip" }
        val (outW, outH) = outputSize(project, settings)

        val videoItems = project.clips.map { clip -> editedItem(clip, outW, outH, settings) }
        val sequences = mutableListOf(EditedMediaItemSequence(videoItems, /* isLooping = */ false))

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
            sequences += EditedMediaItemSequence(listOf(musicItem), music.loop)
        }

        val compositionVideoEffects = buildList<Effect> {
            if (TimelineFadeMatrix.needed(project)) add(TimelineFadeMatrix(project))
            if (project.texts.isNotEmpty()) add(OverlayEffect(ImmutableList.of<TextureOverlay>(TimedTextOverlay(project, outW, outH))))
            if (settings.frameRate > 0) add(FrameDropEffect.createDefaultFrameDropEffect(settings.frameRate.toFloat()))
        }

        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), compositionVideoEffects))
            // Clips without audio (or muted) still produce a continuous audio track.
            .experimentalSetForceAudioTrack(true)
            .build()
    }

    private fun editedItem(clip: Clip, outW: Int, outH: Int, settings: VideoExportSettings): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(clip.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.trimStartMs)
                    .setEndPositionMs(clip.trimEndMs)
                    .build(),
            )
            .build()

        val video = buildList<Effect> {
            if (clip.speed != 1f) add(SpeedChangeEffect(clip.speed))
            if (clip.quarterTurns != 0 || clip.flipH || clip.flipV) {
                add(
                    ScaleAndRotateTransformation.Builder()
                        .setScale(if (clip.flipH) -1f else 1f, if (clip.flipV) -1f else 1f)
                        // Media3 rotates counter-clockwise for positive degrees.
                        .setRotationDegrees(-90f * clip.quarterTurns)
                        .build(),
                )
            }
            if (ClipColorMatrix.needed(clip)) add(ClipColorMatrix(clip))
            // Every clip is fitted to the same output frame (letterboxed if needed).
            add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
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
        for (ch in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.create(ch, ch).scaleBy(gain))
    }

    /** Suggested video bitrate for a frame size, frame rate and quality. */
    fun bitrate(w: Int, h: Int, fps: Int, quality: ExportQuality, codec: ExportCodec): Int {
        val f = if (fps > 0) fps else 30
        val base = w.toLong() * h * f * quality.bitsPerPixel * (if (codec == ExportCodec.HEVC) 0.65f else 1f)
        return base.toLong().coerceIn(1_000_000L, 120_000_000L).toInt()
    }
}
