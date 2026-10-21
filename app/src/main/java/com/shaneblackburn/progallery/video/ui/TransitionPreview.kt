package com.shaneblackburn.progallery.video.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.shaneblackburn.progallery.core.video.TransitionCpu
import com.shaneblackburn.progallery.core.video.TransitionDirection
import com.shaneblackburn.progallery.core.video.TransitionSample
import com.shaneblackburn.progallery.core.video.TransitionType
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders faithful transition thumbnails/demos for the picker, using the SAME maths
 * ([TransitionCpu]) the export shader runs. Two distinct built-in "clips" make the effect and
 * its direction obvious. This runs on the CPU, so previews work everywhere (no GPU/Media3
 * needed) and always match what will actually be exported.
 */
object TransitionPreview {
    /** Warm clip with a white disc near the LEFT (so pans/wipes reveal their direction). */
    private fun imgA(u: Float, v: Float): FloatArray {
        var r = 0.88f - 0.22f * v + 0.08f * sin(u * 20f)
        var g = 0.38f + 0.22f * u
        var b = 0.14f
        if (sqrt((u - 0.27f) * (u - 0.27f) + (v - 0.42f) * (v - 0.42f)) < 0.14f) { r = 1f; g = 1f; b = 1f }
        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** Cool clip with a white disc near the RIGHT. */
    private fun imgB(u: Float, v: Float): FloatArray {
        var r = 0.12f
        var g = 0.42f + 0.28f * v + 0.07f * sin(v * 18f)
        var b = 0.82f - 0.18f * u
        if (sqrt((u - 0.73f) * (u - 0.73f) + (v - 0.60f) * (v - 0.60f)) < 0.14f) { r = 1f; g = 1f; b = 1f }
        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** One representative frame: outgoing clip A's tail, or incoming clip B's head, at [edge]. */
    fun frame(
        type: TransitionType,
        edge: Float,
        incoming: Boolean,
        w: Int,
        h: Int,
        direction: TransitionDirection = TransitionDirection.AUTO,
        intensity: Float = 0.7f,
        seed: Float = 7f,
    ): ImageBitmap {
        val u = TransitionSample(type, edge, incoming, direction, intensity).uniforms()
        val src: (Float, Float) -> FloatArray = if (incoming) ::imgB else ::imgA
        val px = IntArray(w * h)
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val c = TransitionCpu.render(u, (xx + 0.5f) / w, (yy + 0.5f) / h, seed, src)
                val r = (c[0] * 255f).toInt().coerceIn(0, 255)
                val g = (c[1] * 255f).toInt().coerceIn(0, 255)
                val b = (c[2] * 255f).toInt().coerceIn(0, 255)
                px[yy * w + xx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp.asImageBitmap()
    }

    /**
     * The 8-step demo sequence across one cut: four outgoing frames on clip A (edge 0 → 1) then
     * four incoming frames on clip B (edge 1 → 0). Used to animate a transition in the picker.
     */
    private val demoSteps = listOf(
        false to 0.0f, false to 0.45f, false to 0.8f, false to 1.0f,
        true to 1.0f, true to 0.8f, true to 0.45f, true to 0.0f,
    )

    const val DEMO_FRAMES = 8

    /** Demo frame [step] (0..7) for an animated preview of [type]. */
    fun demoFrame(type: TransitionType, step: Int, w: Int, h: Int,
                  direction: TransitionDirection = TransitionDirection.AUTO, intensity: Float = 0.7f): ImageBitmap {
        val (incoming, edge) = demoSteps[step.coerceIn(0, demoSteps.lastIndex)]
        return frame(type, edge, incoming, w, h, direction, intensity, seed = step * 13f)
    }
}
