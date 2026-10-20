package com.shaneblackburn.progallery.core.edit

/**
 * Built-in looks. Each is expressed as an [Adjustments] delta plus an optional colour
 * matrix and split-tone, so it composes with the user's own adjustments and can be
 * blended by intensity.
 */
enum class FilterLook(val label: String, val group: String) {
    NONE("Original", "Basic"),
    VIVID("Vivid", "Basic"),
    WARM("Warm", "Basic"),
    COOL("Cool", "Basic"),
    MONO("Mono", "B&W"),
    NOIR("Noir", "B&W"),
    SILVER("Silver", "B&W"),
    SEPIA("Sepia", "Vintage"),
    VINTAGE("Vintage", "Vintage"),
    FADED("Faded", "Vintage"),
    POLAROID("Instant", "Vintage"),
    CINEMATIC("Teal & Orange", "Cinematic"),
    BLOCKBUSTER("Blockbuster", "Cinematic"),
    MOODY("Moody", "Cinematic"),
    BLEACH("Bleach Bypass", "Cinematic"),
    GOLDEN("Golden Hour", "Colour"),
    PASTEL("Pastel", "Colour"),
    LUSH("Lush", "Colour"),
    DRAMA("Drama", "Colour"),
}

/** Concrete parameters of a look. */
data class LookSpec(
    val adjust: Adjustments = Adjustments(),
    /** 4x5 row-major colour matrix on 0..1 values, or null. */
    val matrix: FloatArray? = null,
    /** Split toning: shadow and highlight tint colours (r,g,b 0..1) and strength. */
    val shadowTint: FloatArray? = null,
    val highlightTint: FloatArray? = null,
    val splitStrength: Float = 0f,
    /** Lift of the black point (0..0.3) for faded film looks. */
    val fade: Float = 0f,
) {
    override fun equals(other: Any?): Boolean = other is LookSpec && adjust == other.adjust &&
        matrix.contentEquals(other.matrix) && shadowTint.contentEquals(other.shadowTint) &&
        highlightTint.contentEquals(other.highlightTint) && splitStrength == other.splitStrength && fade == other.fade

    override fun hashCode(): Int = adjust.hashCode()
}

private fun FloatArray?.contentEquals(o: FloatArray?): Boolean =
    if (this == null || o == null) this === o else this.contentEquals(o)

object Looks {

    private val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

    private fun mono(r: Float = LUMA[0], g: Float = LUMA[1], b: Float = LUMA[2]) = floatArrayOf(
        r, g, b, 0f, 0f,
        r, g, b, 0f, 0f,
        r, g, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    private val SEPIA_MATRIX = floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    fun spec(look: FilterLook): LookSpec = when (look) {
        FilterLook.NONE -> LookSpec()
        FilterLook.VIVID -> LookSpec(Adjustments(contrast = 0.15f, saturation = 0.25f, vibrance = 0.3f))
        FilterLook.WARM -> LookSpec(Adjustments(temperature = 0.35f, saturation = 0.05f))
        FilterLook.COOL -> LookSpec(Adjustments(temperature = -0.35f, tint = 0.05f))
        FilterLook.MONO -> LookSpec(matrix = mono())
        FilterLook.NOIR -> LookSpec(Adjustments(contrast = 0.45f, blacks = -0.2f), matrix = mono(0.3f, 0.6f, 0.1f))
        FilterLook.SILVER -> LookSpec(Adjustments(contrast = -0.1f, highlights = -0.2f), matrix = mono(), fade = 0.06f)
        FilterLook.SEPIA -> LookSpec(matrix = SEPIA_MATRIX)
        FilterLook.VINTAGE -> LookSpec(
            Adjustments(contrast = -0.1f, saturation = -0.25f, temperature = 0.2f, vignette = 0.3f),
            shadowTint = floatArrayOf(0.1f, 0.2f, 0.35f), highlightTint = floatArrayOf(1f, 0.85f, 0.6f),
            splitStrength = 0.25f, fade = 0.08f,
        )
        FilterLook.FADED -> LookSpec(Adjustments(contrast = -0.2f, saturation = -0.3f), fade = 0.12f)
        FilterLook.POLAROID -> LookSpec(
            Adjustments(contrast = -0.05f, saturation = -0.1f, temperature = 0.15f, tint = -0.05f),
            highlightTint = floatArrayOf(1f, 0.95f, 0.8f), shadowTint = floatArrayOf(0.15f, 0.3f, 0.3f),
            splitStrength = 0.3f, fade = 0.1f,
        )
        FilterLook.CINEMATIC -> LookSpec(
            Adjustments(contrast = 0.15f, saturation = -0.05f),
            shadowTint = floatArrayOf(0f, 0.45f, 0.55f), highlightTint = floatArrayOf(1f, 0.65f, 0.35f), splitStrength = 0.45f,
        )
        FilterLook.BLOCKBUSTER -> LookSpec(
            Adjustments(contrast = 0.3f, saturation = 0.1f, vignette = 0.25f, clarity = 0.2f),
            shadowTint = floatArrayOf(0f, 0.35f, 0.5f), highlightTint = floatArrayOf(1f, 0.7f, 0.4f), splitStrength = 0.35f,
        )
        FilterLook.MOODY -> LookSpec(
            Adjustments(exposure = -0.1f, contrast = 0.2f, saturation = -0.3f, highlights = -0.3f, vignette = 0.35f),
            shadowTint = floatArrayOf(0.1f, 0.15f, 0.3f), splitStrength = 0.25f,
        )
        FilterLook.BLEACH -> LookSpec(Adjustments(contrast = 0.4f, saturation = -0.55f, clarity = 0.2f))
        FilterLook.GOLDEN -> LookSpec(
            Adjustments(temperature = 0.45f, tint = 0.08f, saturation = 0.1f, highlights = -0.15f),
            highlightTint = floatArrayOf(1f, 0.8f, 0.45f), splitStrength = 0.25f,
        )
        FilterLook.PASTEL -> LookSpec(Adjustments(contrast = -0.3f, saturation = -0.2f, brightness = 0.1f), fade = 0.1f)
        FilterLook.LUSH -> LookSpec(Adjustments(saturation = 0.15f, vibrance = 0.35f, tint = -0.1f, contrast = 0.1f))
        FilterLook.DRAMA -> LookSpec(Adjustments(contrast = 0.35f, clarity = 0.45f, highlights = -0.35f, shadows = 0.25f, vignette = 0.2f))
    }

    /**
     * Combines the user's adjustments with a look's adjustments scaled by [intensity].
     * Values add and are clamped to each slider's range.
     */
    fun combine(user: Adjustments, look: FilterLook, intensity: Float): Adjustments {
        if (look == FilterLook.NONE || intensity <= 0f) return user
        val la = spec(look).adjust
        var out = user
        for (k in AdjustKey.entries) {
            val v = k.get(la)
            if (v != 0f) out = k.set(out, k.get(out) + v * intensity)
        }
        return out
    }
}
