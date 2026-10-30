package com.shaneblackburn.progallery.video

import android.graphics.Bitmap
import com.shaneblackburn.progallery.core.video.TransitionCpu
import com.shaneblackburn.progallery.core.video.TransitionUniforms
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Applies a transition to a single composed-preview frame on the CPU, using the shared reference
 * [TransitionCpu] — the exact per-pixel maths the GPU shader ([ClipTransitionEffect]) and the export
 * run — so the preview stays consistent with what will be exported.
 *
 * Why this exists: the composed preview extracts the clip's base frame (colour/filter/geometry) with
 * Media3's FrameExtractor, whose internal frame is keyed on the source position. A position-dependent
 * transition baked into that pipeline could be served from a stale cached frame while scrubbing
 * (backwards in particular), freezing the transition. Applying the transition here, from the exact
 * playhead position, every render, removes that whole class of staleness: there is no transition in
 * the cached extractor to go stale.
 *
 * It runs on the frame-preview thread (never the main thread). Work is capped to [MAX_SHORT_SIDE] so
 * the 49-tap blur used by a few transitions stays responsive during a scrub; the result is scaled
 * back up to the source frame's size so the preview's dimensions don't change between frames. This is
 * a paused/scrub still, not real-time playback, so a brief settle on blur-heavy transitions is fine.
 */
object TransitionBitmap {

    /** Cap on the CPU render's short side; bounds per-pixel cost on large previews. */
    private const val MAX_SHORT_SIDE = 540

    /**
     * Returns a new bitmap with [u] applied to [src] at animation [seed]. Returns [src] unchanged
     * when [u] is identity. The caller owns both bitmaps; [src] is not recycled here.
     */
    fun apply(src: Bitmap, u: TransitionUniforms, seed: Float): Bitmap {
        if (u.isIdentity) return src
        val w0 = src.width
        val h0 = src.height
        if (w0 < 2 || h0 < 2) return src

        // Work at a capped resolution, then scale the result back to the source size.
        val shortSide = min(w0, h0)
        val scale = if (shortSide > MAX_SHORT_SIDE) MAX_SHORT_SIDE.toFloat() / shortSide else 1f
        val w = max(2, (w0 * scale).roundToInt())
        val h = max(2, (h0 * scale).roundToInt())
        val work = if (w != w0 || h != h0) Bitmap.createScaledBitmap(src, w, h, true) else src

        val inPix = IntArray(w * h)
        work.getPixels(inPix, 0, w, 0, 0, w, h)

        // One reusable sampler result: TransitionCpu copies the values out of it on every tap before
        // the next call, so a single shared array is safe and avoids per-tap allocation.
        val sample = FloatArray(3)
        val sampler: (Float, Float) -> FloatArray = { su, sv ->
            val fx = (su * (w - 1)).coerceIn(0f, (w - 1).toFloat())
            val fy = (sv * (h - 1)).coerceIn(0f, (h - 1).toFloat())
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val tx = fx - x0; val ty = fy - y0
            val c00 = inPix[y0 * w + x0]; val c10 = inPix[y0 * w + x1]
            val c01 = inPix[y1 * w + x0]; val c11 = inPix[y1 * w + x1]
            sample[0] = bilerp(r(c00), r(c10), r(c01), r(c11), tx, ty)
            sample[1] = bilerp(g(c00), g(c10), g(c01), g(c11), tx, ty)
            sample[2] = bilerp(b(c00), b(c10), b(c01), b(c11), tx, ty)
            sample
        }

        val outPix = IntArray(w * h)
        for (y in 0 until h) {
            val ny = (y + 0.5f) / h
            val row = y * w
            for (x in 0 until w) {
                val nx = (x + 0.5f) / w
                val c = TransitionCpu.render(u, nx, ny, seed, sampler)
                outPix[row + x] = argb(c[0], c[1], c[2])
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPix, 0, w, 0, 0, w, h)
        if (work !== src) work.recycle()
        return if (w != w0 || h != h0) {
            val scaled = Bitmap.createScaledBitmap(out, w0, h0, true)
            if (scaled !== out) out.recycle()
            scaled
        } else {
            out
        }
    }

    private fun r(c: Int) = ((c ushr 16) and 0xFF) / 255f
    private fun g(c: Int) = ((c ushr 8) and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f

    private fun bilerp(c00: Float, c10: Float, c01: Float, c11: Float, tx: Float, ty: Float): Float {
        val top = c00 + (c10 - c00) * tx
        val bottom = c01 + (c11 - c01) * tx
        return top + (bottom - top) * ty
    }

    private fun argb(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}
