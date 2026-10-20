package com.shaneblackburn.progallery.core.edit

/**
 * Vector overlays drawn on top of the edited photo. Positions and sizes are normalised
 * to the *output* (post-crop) image: x,y in 0..1, sizes as a fraction of the image's
 * shorter side. This keeps overlays sharp and correctly placed at any export size.
 */
sealed interface Overlay {
    val id: Long

    data class Text(
        override val id: Long,
        val text: String,
        val x: Float,
        val y: Float,
        /** Text height as a fraction of the shorter image side. */
        val size: Float = 0.08f,
        val rotation: Float = 0f,
        val color: Int = 0xFFFFFFFF.toInt(),
        val font: FontChoice = FontChoice.SANS_BOLD,
        val outlineColor: Int? = null,
        val outlineWidth: Float = 0.12f,
        val shadow: Boolean = true,
        val backgroundColor: Int? = null,
        val align: TextAlignChoice = TextAlignChoice.CENTER,
    ) : Overlay

    /** Free-hand stroke (pen, marker, highlighter). */
    data class Stroke(
        override val id: Long,
        val points: List<Pt>,
        val color: Int,
        /** Stroke width as a fraction of the shorter image side. */
        val width: Float,
        val brush: Brush = Brush.PEN,
    ) : Overlay

    data class Shape(
        override val id: Long,
        val kind: ShapeKind,
        val start: Pt,
        val end: Pt,
        val color: Int,
        val width: Float = 0.01f,
        val filled: Boolean = false,
    ) : Overlay

    data class Sticker(
        override val id: Long,
        /** Emoji or symbol rendered as text, so no bundled image assets are needed. */
        val glyph: String,
        val x: Float,
        val y: Float,
        val size: Float = 0.18f,
        val rotation: Float = 0f,
    ) : Overlay
}

data class Pt(val x: Float, val y: Float)

enum class Brush(val label: String) { PEN("Pen"), MARKER("Marker"), HIGHLIGHTER("Highlighter"), ERASER("Eraser") }

enum class ShapeKind(val label: String) { LINE("Line"), ARROW("Arrow"), RECT("Rectangle"), OVAL("Oval") }

enum class FontChoice(val label: String) {
    SANS("Sans"), SANS_BOLD("Bold"), SERIF("Serif"), MONO("Mono"), CONDENSED("Condensed"), SCRIPT("Handwritten"),
}

enum class TextAlignChoice { LEFT, CENTER, RIGHT }

/** Border/frame around the final image. */
data class Frame(
    val style: FrameStyle,
    /** Border thickness as a fraction of the shorter side. */
    val thickness: Float = 0.05f,
    val color: Int = 0xFFFFFFFF.toInt(),
    /** Corner radius of the photo inside the frame, fraction of shorter side. */
    val cornerRadius: Float = 0f,
)

enum class FrameStyle(val label: String) {
    SOLID("Border"),
    POLAROID("Instant"),
    ROUNDED("Rounded"),
    SQUARE_FIT("Square fit"),
}

object FrameMath {
    /**
     * Output canvas size and the inset rect (left, top, width, height) where the photo
     * goes, for a photo of [w]×[h].
     */
    fun layout(frame: Frame, w: Int, h: Int): Pair<Pair<Int, Int>, IntArray> {
        val short = minOf(w, h)
        val t = Math.round(frame.thickness.coerceIn(0f, 0.3f) * short)
        return when (frame.style) {
            FrameStyle.SOLID, FrameStyle.ROUNDED -> (w + 2 * t to h + 2 * t) to intArrayOf(t, t, w, h)
            FrameStyle.POLAROID -> {
                val bottom = t * 4
                (w + 2 * t to h + t + bottom) to intArrayOf(t, t, w, h)
            }
            FrameStyle.SQUARE_FIT -> {
                val side = maxOf(w, h) + 2 * t
                (side to side) to intArrayOf((side - w) / 2, (side - h) / 2, w, h)
            }
        }
    }
}
