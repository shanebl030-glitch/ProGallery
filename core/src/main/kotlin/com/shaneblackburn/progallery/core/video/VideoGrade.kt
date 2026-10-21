package com.shaneblackburn.progallery.core.video

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The video colour grade, as numbers the GPU shader consumes, plus a CPU reference
 * implementation of the same per-pixel maths (used by tests to check the shader output).
 *
 * Values are display-referred (gamma-encoded) 0..1, matching Media3's default SDR
 * working space. Order: exposure -> white balance -> brightness -> shadows/highlights ->
 * contrast -> saturation/vibrance -> clamp. Sharpness is a neighbourhood operation and
 * exists only in the shader.
 */
data class VideoGrade(
    val expGain: Float,
    val wbR: Float,
    val wbG: Float,
    val wbB: Float,
    val brightness: Float,
    val shadows: Float,
    val highlights: Float,
    val contrast: Float,
    val saturation: Float,
    val vibrance: Float,
    val sharpness: Float,
) {
    /** Grades one pixel (0..1 channels) in place into [out]; returns [out]. */
    fun apply(r0: Float, g0: Float, b0: Float, out: FloatArray = FloatArray(3)): FloatArray {
        var r = r0; var g = g0; var b = b0
        if (expGain != 1f) {
            r = (r.coerceAtLeast(0f).pow(GAMMA) * expGain).pow(1f / GAMMA)
            g = (g.coerceAtLeast(0f).pow(GAMMA) * expGain).pow(1f / GAMMA)
            b = (b.coerceAtLeast(0f).pow(GAMMA) * expGain).pow(1f / GAMMA)
        }
        r *= wbR; g *= wbG; b *= wbB
        r += brightness; g += brightness; b += brightness
        val l1 = luma(r, g, b).coerceIn(0f, 1f)
        val tone = shadows * (1f - smoothstep(0f, 0.6f, l1)) + highlights * smoothstep(0.4f, 1f, l1)
        r += tone; g += tone; b += tone
        r = (r - 0.5f) * contrast + 0.5f
        g = (g - 0.5f) * contrast + 0.5f
        b = (b - 0.5f) * contrast + 0.5f
        val l2 = luma(r, g, b)
        val sat = (max(r, max(g, b)) - min(r, min(g, b))).coerceIn(0f, 1f)
        val f = saturation * (1f + vibrance * (1f - sat))
        r = l2 + (r - l2) * f; g = l2 + (g - l2) * f; b = l2 + (b - l2) * f
        out[0] = r.coerceIn(0f, 1f); out[1] = g.coerceIn(0f, 1f); out[2] = b.coerceIn(0f, 1f)
        return out
    }

    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        const val GAMMA = 2.2f
        val IDENTITY = from(VideoColor())

        fun from(c: VideoColor) = VideoGrade(
            expGain = 2f.pow(c.exposure * 2f),
            wbR = 1f + 0.2f * c.temperature,
            wbG = 1f - 0.15f * c.tint,
            wbB = 1f - 0.2f * c.temperature,
            brightness = c.brightness * 0.25f,
            shadows = c.shadows * 0.25f,
            highlights = c.highlights * 0.25f,
            contrast = 1f + c.contrast * 0.6f,
            saturation = 1f + c.saturation,
            vibrance = c.vibrance,
            sharpness = c.sharpness * 1.5f,
        )

        fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
