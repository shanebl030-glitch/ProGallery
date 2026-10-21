package com.shaneblackburn.progallery.core.video

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Non-destructive description of a video edit: an ordered list of clips plus
 * project-wide overlays, music and fades. All times are milliseconds.
 *
 * "Source time" is a position inside a clip's original file. "Timeline time" is a
 * position in the exported video (after trims and speed changes).
 */
data class VideoProject(
    val clips: List<Clip> = emptyList(),
    /** Transition into clip i+1 is transitions[i]; missing entries mean a hard cut. */
    val transitions: List<Transition> = emptyList(),
    val texts: List<TimedText> = emptyList(),
    val music: MusicTrack? = null,
    val fadeIn: Fade? = null,
    val fadeOut: Fade? = null,
    val aspect: AspectMode = AspectMode.ORIGINAL,
) {
    val durationMs: Long get() = clips.sumOf { it.timelineDurationMs }

    val isEmpty: Boolean get() = clips.isEmpty()

    fun transitionAfter(index: Int): Transition = transitions.getOrNull(index) ?: Transition.NONE

    /** Timeline start time of each clip. */
    fun clipStarts(): LongArray {
        val out = LongArray(clips.size)
        var t = 0L
        clips.forEachIndexed { i, c -> out[i] = t; t += c.timelineDurationMs }
        return out
    }

    /** Which clip plays at [timelineMs] and where inside its source file. */
    fun locate(timelineMs: Long): Location? {
        if (clips.isEmpty()) return null
        val t = timelineMs.coerceIn(0, max(0, durationMs - 1))
        var start = 0L
        clips.forEachIndexed { i, c ->
            val end = start + c.timelineDurationMs
            if (t < end || i == clips.lastIndex) {
                return Location(i, c.sourceTimeAt(t - start), t - start)
            }
            start = end
        }
        return null
    }

    /**
     * Splits the clip under [timelineMs] into two at that point. Returns this project
     * unchanged if the split would create a piece shorter than [minPieceMs].
     */
    fun splitAt(timelineMs: Long, minPieceMs: Long = MIN_CLIP_MS): VideoProject {
        val loc = locate(timelineMs) ?: return this
        val clip = clips[loc.clipIndex]
        val cut = loc.sourceMs
        val minPiece = if (clip.isImage) max(minPieceMs, Clip.MIN_IMAGE_MS) else minPieceMs
        if (cut - clip.trimStartMs < minPiece || clip.trimEndMs - cut < minPiece) return this
        // A photo has no source offset: splitting gives two photos sharing the display time.
        val first = if (clip.isImage) clip.withImageDuration(cut) else clip.copy(trimEndMs = cut)
        val second = if (clip.isImage) clip.copy(id = clip.id * 31 + cut).withImageDuration(clip.trimEndMs - cut)
        else clip.copy(id = clip.id * 31 + cut, trimStartMs = cut)
        val newClips = clips.toMutableList().apply { set(loc.clipIndex, first); add(loc.clipIndex + 1, second) }
        // A hard cut between the two halves; the old "after" transition moves to the second half.
        val slots = transitionSlots().apply { add(loc.clipIndex, Transition.NONE) }
        return copy(clips = newClips, transitions = slots)
    }

    fun removeClip(index: Int): VideoProject {
        if (index !in clips.indices) return this
        val slots = transitionSlots()
        if (slots.isNotEmpty()) slots.removeAt(if (index == clips.lastIndex) index - 1 else index)
        return copy(clips = clips.toMutableList().apply { removeAt(index) }, transitions = slots)
    }

    /** Exactly clips.size - 1 transition slots (missing ones filled with cuts). */
    private fun transitionSlots(): MutableList<Transition> =
        MutableList(max(0, clips.size - 1)) { transitionAfter(it) }

    fun moveClip(from: Int, to: Int): VideoProject {
        if (from !in clips.indices || to !in clips.indices || from == to) return this
        val list = clips.toMutableList()
        val c = list.removeAt(from)
        list.add(to, c)
        // Transitions belong to positions, not clips; keep them as they are.
        return copy(clips = list)
    }

    /**
     * Drag-to-reorder: the position clip [from] lands at after being dragged by [deltaMs]
     * of timeline time — the number of other clips whose centre lies before its new centre.
     */
    fun dropIndex(from: Int, deltaMs: Float): Int {
        if (from !in clips.indices) return from
        val starts = clipStarts()
        val centre = starts[from] + clips[from].timelineDurationMs / 2f + deltaMs
        var target = 0
        for (j in clips.indices) {
            if (j != from && starts[j] + clips[j].timelineDurationMs / 2f < centre) target++
        }
        return target
    }

    fun updateClip(index: Int, change: (Clip) -> Clip): VideoProject =
        if (index !in clips.indices) this else copy(clips = clips.toMutableList().also { it[index] = change(it[index]) })

    fun setTransition(afterIndex: Int, t: Transition): VideoProject {
        if (afterIndex !in 0 until clips.size - 1) return this
        val list = transitionSlots()
        list[afterIndex] = t
        return copy(transitions = list)
    }

    data class Location(val clipIndex: Int, val sourceMs: Long, val offsetInClipMs: Long)

    companion object {
        const val MIN_CLIP_MS = 100L
    }
}

data class Clip(
    val id: Long,
    val uri: String,
    val name: String,
    val sourceDurationMs: Long,
    val width: Int,
    val height: Int,
    /** Rotation stored in the file's metadata (0/90/180/270). */
    val sourceRotation: Int = 0,
    val hasAudio: Boolean = true,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = sourceDurationMs,
    /** Playback speed multiplier, 0.25..4. */
    val speed: Float = 1f,
    /** Original audio gain 0..2 (1 = unchanged). */
    val volume: Float = 1f,
    val muted: Boolean = false,
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val color: VideoColor = VideoColor(),
    val filter: VideoFilter = VideoFilter.NONE,
    /** 0..1 strength of [filter]. */
    val filterIntensity: Float = 1f,
    /**
     * A still photo shown for [trimmedSourceMs] (its "duration"). Photos have no audio,
     * no speed change and no trimming; [sourceDurationMs] is simply the display time.
     */
    val isImage: Boolean = false,
    /** MIME type of the source (needed to load photos), if known. */
    val mimeType: String? = null,
) {
    val trimmedSourceMs: Long get() = max(0L, trimEndMs - trimStartMs)

    val timelineDurationMs: Long get() = (trimmedSourceMs / speed.toDouble()).roundToLong()

    fun sourceTimeAt(offsetInClipMs: Long): Long =
        (trimStartMs + offsetInClipMs * speed.toDouble()).roundToLong().coerceIn(trimStartMs, trimEndMs)

    /** For photo clips: sets how long the photo is shown (0.5 s .. 60 s). */
    fun withImageDuration(ms: Long): Clip {
        val d = ms.coerceIn(MIN_IMAGE_MS, MAX_IMAGE_MS)
        return copy(sourceDurationMs = d, trimStartMs = 0L, trimEndMs = d, speed = 1f)
    }

    /** Clamps trims to the source and keeps at least [VideoProject.MIN_CLIP_MS]. */
    fun withTrim(start: Long, end: Long): Clip {
        val s = start.coerceIn(0L, max(0L, sourceDurationMs - VideoProject.MIN_CLIP_MS))
        val e = end.coerceIn(min(sourceDurationMs, s + VideoProject.MIN_CLIP_MS), sourceDurationMs)
        return copy(trimStartMs = s, trimEndMs = e)
    }

    /** Output frame size after rotation metadata and user quarter turns. */
    val orientedWidth: Int get() = if ((sourceRotation / 90 + quarterTurns) % 2 == 1) height else width
    val orientedHeight: Int get() = if ((sourceRotation / 90 + quarterTurns) % 2 == 1) width else height

    companion object {
        const val DEFAULT_IMAGE_MS = 3000L
        const val MIN_IMAGE_MS = 500L
        const val MAX_IMAGE_MS = 60_000L
    }
}

/**
 * Colour grade for a video clip. All values -1..1 with 0 = neutral, except
 * [sharpness] which is 0..1. Applied on the GPU by the grading shader, identically in
 * the live preview and the export.
 */
data class VideoColor(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    /** In stops / 2 (so -1..1 = -2..+2 EV). */
    val exposure: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val vibrance: Float = 0f,
    /** Green (-) to magenta (+). */
    val tint: Float = 0f,
    val sharpness: Float = 0f,
) {
    val isNeutral: Boolean get() = this == VideoColor()
}

enum class VideoFilter(val label: String) {
    NONE("Original"), MONO("Mono"), SEPIA("Sepia"), WARM("Warm"), COOL("Cool"),
    VIVID("Vivid"), FADED("Faded"), CINEMATIC("Cinematic"), NOIR("Noir"),
}

enum class TransitionType(val label: String) { NONE("Cut"), FADE_BLACK("Fade through black"), FADE_WHITE("Fade through white") }

data class Transition(val type: TransitionType, val durationMs: Long = 600L) {
    companion object { val NONE = Transition(TransitionType.NONE, 0L) }
}

data class Fade(val durationMs: Long, val toWhite: Boolean = false)

enum class AspectMode(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null), SQUARE("1:1", 1f), PORTRAIT("9:16", 9f / 16f), LANDSCAPE("16:9", 16f / 9f), FOUR_FIVE("4:5", 4f / 5f),
}

data class TimedText(
    val id: Long,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    /** Centre position, normalised 0..1. */
    val x: Float = 0.5f,
    val y: Float = 0.85f,
    /** Text height as a fraction of the output height. */
    val size: Float = 0.06f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val background: Boolean = true,
    val animation: TextAnimation = TextAnimation.FADE,
) {
    fun isVisibleAt(t: Long) = t in startMs until endMs

    /** 0..1 opacity at timeline time [t], including the in/out animation. */
    fun alphaAt(t: Long, animMs: Long = 300L): Float {
        if (!isVisibleAt(t)) return 0f
        if (animation == TextAnimation.NONE) return 1f
        val inP = ((t - startMs).toFloat() / animMs).coerceIn(0f, 1f)
        val outP = ((endMs - t).toFloat() / animMs).coerceIn(0f, 1f)
        return min(inP, outP)
    }
}

enum class TextAnimation(val label: String) { NONE("None"), FADE("Fade"), SLIDE_UP("Slide up"), POP("Pop") }

data class MusicTrack(
    val uri: String,
    val name: String,
    val durationMs: Long,
    /** Where in the music file to start. */
    val startOffsetMs: Long = 0L,
    /** Gain 0..2. */
    val volume: Float = 0.8f,
    val fadeInMs: Long = 1000L,
    val fadeOutMs: Long = 1500L,
    val loop: Boolean = true,
)

/** Audio gain envelope helpers shared by preview and export. */
object AudioEnvelope {
    /** Gain at [t] for a track of [total] ms with fades. */
    fun gain(t: Long, total: Long, base: Float, fadeInMs: Long, fadeOutMs: Long): Float {
        if (t < 0 || t > total) return 0f
        var g = base
        if (fadeInMs > 0 && t < fadeInMs) g *= t.toFloat() / fadeInMs
        if (fadeOutMs > 0 && t > total - fadeOutMs) g *= ((total - t).toFloat() / fadeOutMs).coerceIn(0f, 1f)
        return g.coerceIn(0f, 4f)
    }
}

/** Brightness multiplier (0..1) for fades and fade-through transitions at timeline time [t]. */
object FadeCurve {
    /**
     * Returns (amount 0..1, toWhite) where amount is how far towards black/white the frame
     * should be blended at [t].
     */
    fun at(project: VideoProject, t: Long): Pair<Float, Boolean> {
        val total = project.durationMs
        var amount = 0f
        var white = false
        project.fadeIn?.let { f ->
            if (f.durationMs > 0 && t < f.durationMs) { amount = max(amount, 1f - t.toFloat() / f.durationMs); white = f.toWhite }
        }
        project.fadeOut?.let { f ->
            if (f.durationMs > 0 && t > total - f.durationMs) {
                val a = ((t - (total - f.durationMs)).toFloat() / f.durationMs).coerceIn(0f, 1f)
                if (a > amount) { amount = a; white = f.toWhite }
            }
        }
        val starts = project.clipStarts()
        for (i in 0 until project.clips.size - 1) {
            val tr = project.transitionAfter(i)
            if (tr.type == TransitionType.NONE || tr.durationMs <= 0) continue
            val boundary = starts[i + 1]
            val half = tr.durationMs / 2
            val d = kotlin.math.abs(t - boundary)
            if (d < half) {
                val a = 1f - d.toFloat() / half
                if (a > amount) { amount = a; white = tr.type == TransitionType.FADE_WHITE }
            }
        }
        return amount.coerceIn(0f, 1f) to white
    }
}
