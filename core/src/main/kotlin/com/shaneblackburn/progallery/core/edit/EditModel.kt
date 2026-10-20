package com.shaneblackburn.progallery.core.edit

/**
 * Non-destructive description of every edit applied to a photo. The original file is
 * never touched; rendering applies this description to the original pixels.
 *
 * Geometry is applied first (rotate 90° steps → flip → straighten → crop), then tone
 * and colour adjustments, then the look/filter, then detail/effects, then overlays.
 */
data class EditState(
    val geometry: Geometry = Geometry(),
    val adjust: Adjustments = Adjustments(),
    val filter: FilterLook = FilterLook.NONE,
    val filterIntensity: Float = 1f,
    val curves: ToneCurve = ToneCurve.IDENTITY,
    val hsl: HslAdjust = HslAdjust(),
    val overlays: List<Overlay> = emptyList(),
    val frame: Frame? = null,
) {
    val isIdentity: Boolean get() = this == EditState()
}

/**
 * Crop is stored in normalised coordinates (0..1) of the image *after* rotation, flip
 * and straighten, so it survives resizing for export.
 */
data class Geometry(
    /** Quarter turns clockwise: 0..3. */
    val quarterTurns: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Fine rotation in degrees, -45..45. */
    val straighten: Float = 0f,
    val crop: NormRect = NormRect.FULL,
) {
    fun rotateCw() = copy(quarterTurns = (quarterTurns + 1) % 4, crop = crop.rotatedCw())
    fun rotateCcw() = copy(quarterTurns = (quarterTurns + 3) % 4, crop = crop.rotatedCw().rotatedCw().rotatedCw())
    fun flippedH() = copy(flipH = !flipH, crop = crop.flippedH())
    fun flippedV() = copy(flipV = !flipV, crop = crop.flippedV())

    /** Size of the oriented (pre-crop) image for a source of [w]×[h]. */
    fun orientedSize(w: Int, h: Int): Pair<Int, Int> = if (quarterTurns % 2 == 1) h to w else w to h

    /** Output size after crop for a source of [w]×[h] (at full resolution). */
    fun outputSize(w: Int, h: Int): Pair<Int, Int> {
        val (ow, oh) = orientedSize(w, h)
        return maxOf(1, Math.round(crop.width * ow)) to maxOf(1, Math.round(crop.height * oh))
    }
}

data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    fun rotatedCw() = NormRect(1f - bottom, left, 1f - top, right)
    fun flippedH() = NormRect(1f - right, top, 1f - left, bottom)
    fun flippedV() = NormRect(left, 1f - bottom, right, 1f - top)

    fun clamped(minSize: Float = 0.02f): NormRect {
        val l = left.coerceIn(0f, 1f - minSize)
        val t = top.coerceIn(0f, 1f - minSize)
        val r = right.coerceIn(l + minSize, 1f)
        val b = bottom.coerceIn(t + minSize, 1f)
        return NormRect(l, t, r, b)
    }

    companion object {
        val FULL = NormRect(0f, 0f, 1f, 1f)

        /**
         * Largest centred rect with pixel aspect [ratio] (w/h) inside an image of
         * [imgW]×[imgH], in normalised coordinates.
         */
        fun centredAspect(ratio: Float, imgW: Int, imgH: Int): NormRect {
            val imgRatio = imgW.toFloat() / imgH
            return if (ratio > imgRatio) {
                val h = imgRatio / ratio
                NormRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
            } else {
                val w = ratio / imgRatio
                NormRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
            }
        }
    }
}

/** All slider values are -1..1 (0 = neutral) unless noted. */
data class Adjustments(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    /** 0..1 */
    val sharpness: Float = 0f,
    /** -1..1 (negative softens local contrast) */
    val clarity: Float = 0f,
    /** 0..1 */
    val noiseReduction: Float = 0f,
    /** 0..1 */
    val blur: Float = 0f,
    /** -1..1 (negative = white vignette) */
    val vignette: Float = 0f,
    /** 0..1 */
    val grain: Float = 0f,
) {
    val isNeutral: Boolean get() = this == Adjustments()

    /** True if only per-pixel (no neighbourhood) operations are active. */
    val isPointOnly: Boolean get() = sharpness == 0f && clarity == 0f && noiseReduction == 0f && blur == 0f
}

enum class AdjustKey(val label: String, val min: Float, val max: Float) {
    EXPOSURE("Exposure", -1f, 1f),
    BRIGHTNESS("Brightness", -1f, 1f),
    CONTRAST("Contrast", -1f, 1f),
    HIGHLIGHTS("Highlights", -1f, 1f),
    SHADOWS("Shadows", -1f, 1f),
    WHITES("Whites", -1f, 1f),
    BLACKS("Blacks", -1f, 1f),
    SATURATION("Saturation", -1f, 1f),
    VIBRANCE("Vibrance", -1f, 1f),
    TEMPERATURE("Warmth", -1f, 1f),
    TINT("Tint", -1f, 1f),
    SHARPNESS("Sharpen", 0f, 1f),
    CLARITY("Clarity", -1f, 1f),
    NOISE_REDUCTION("Denoise", 0f, 1f),
    BLUR("Blur", 0f, 1f),
    VIGNETTE("Vignette", -1f, 1f),
    GRAIN("Grain", 0f, 1f);

    fun get(a: Adjustments): Float = when (this) {
        EXPOSURE -> a.exposure; BRIGHTNESS -> a.brightness; CONTRAST -> a.contrast
        HIGHLIGHTS -> a.highlights; SHADOWS -> a.shadows; WHITES -> a.whites; BLACKS -> a.blacks
        SATURATION -> a.saturation; VIBRANCE -> a.vibrance; TEMPERATURE -> a.temperature; TINT -> a.tint
        SHARPNESS -> a.sharpness; CLARITY -> a.clarity; NOISE_REDUCTION -> a.noiseReduction
        BLUR -> a.blur; VIGNETTE -> a.vignette; GRAIN -> a.grain
    }

    fun set(a: Adjustments, v: Float): Adjustments {
        val x = v.coerceIn(min, max)
        return when (this) {
            EXPOSURE -> a.copy(exposure = x); BRIGHTNESS -> a.copy(brightness = x); CONTRAST -> a.copy(contrast = x)
            HIGHLIGHTS -> a.copy(highlights = x); SHADOWS -> a.copy(shadows = x); WHITES -> a.copy(whites = x)
            BLACKS -> a.copy(blacks = x); SATURATION -> a.copy(saturation = x); VIBRANCE -> a.copy(vibrance = x)
            TEMPERATURE -> a.copy(temperature = x); TINT -> a.copy(tint = x); SHARPNESS -> a.copy(sharpness = x)
            CLARITY -> a.copy(clarity = x); NOISE_REDUCTION -> a.copy(noiseReduction = x); BLUR -> a.copy(blur = x)
            VIGNETTE -> a.copy(vignette = x); GRAIN -> a.copy(grain = x)
        }
    }
}

/** Per-hue-band hue shift / saturation / luminance, each -1..1. */
data class HslAdjust(val bands: Map<HueBand, HslValue> = emptyMap()) {
    val isNeutral: Boolean get() = bands.values.all { it == HslValue() }
    fun get(b: HueBand) = bands[b] ?: HslValue()
    fun with(b: HueBand, v: HslValue) = copy(bands = bands + (b to v))
}

data class HslValue(val hue: Float = 0f, val saturation: Float = 0f, val luminance: Float = 0f)

enum class HueBand(val label: String, val centreDegrees: Float) {
    RED("Red", 0f), ORANGE("Orange", 30f), YELLOW("Yellow", 60f), GREEN("Green", 120f),
    AQUA("Aqua", 180f), BLUE("Blue", 225f), PURPLE("Purple", 275f), MAGENTA("Magenta", 320f),
}
