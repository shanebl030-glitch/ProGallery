package com.shaneblackburn.progallery.core.edit

import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * CPU reference implementation of every tone, colour and detail edit.
 *
 * Works on packed, non-premultiplied ARGB ints (Android's Bitmap.getPixels format).
 * Geometry (rotate/flip/straighten/crop) and overlays are applied by the platform
 * layer before/after this pipeline.
 *
 * All neighbourhood radii are relative to the image's shorter side, so a 1080 px
 * preview and a 12 MP export look the same.
 *
 * Images are processed in horizontal strips (with overlap for neighbourhood ops),
 * which bounds memory for very large photos and lets rows run in parallel.
 */
class PixelPipeline(state: EditState, private val shortSide: Int) {

    private val adj: Adjustments = Looks.combine(state.adjust, state.filter, state.filterIntensity)
    private val look: LookSpec = Looks.spec(state.filter)
    private val intensity = state.filterIntensity.coerceIn(0f, 1f)
    private val hsl = state.hsl
    private val curveLuts: Array<IntArray>? = if (state.curves.isIdentity) null else state.curves.luts()

    private val expGain = 2.0.pow(adj.exposure * 2.0).toFloat()
    private val rGain = (1f + 0.22f * adj.temperature) * (1f + 0.06f * adj.tint)
    private val gGain = 1f - 0.18f * adj.tint
    private val bGain = (1f - 0.22f * adj.temperature) * (1f + 0.06f * adj.tint)
    private val needsLinear = adj.exposure != 0f || adj.temperature != 0f || adj.tint != 0f

    private val toneLut: FloatArray = buildToneLut(adj, look.fade * intensity)
    private val toneIsIdentity: Boolean = toneLut.withIndex().all { (i, v) -> kotlin.math.abs(v - i / (TONE_SIZE - 1f)) < 1e-4f }

    private val satFactor = 1f + adj.saturation
    private val vib = adj.vibrance
    private val matrix = look.matrix
    private val split = look.splitStrength * intensity

    // Neighbourhood radii in pixels for this resolution.
    private val clarityRadius = max(1, (shortSide * 0.02f).roundToInt())
    private val sharpenRadius = max(1, (shortSide * 0.0012f).roundToInt())
    private val denoiseRadius = max(1, (shortSide * 0.0015f).roundToInt())
    private val blurRadius = max(1, (shortSide * 0.03f * adj.blur).roundToInt())

    /** Rows of context needed above/below each strip. */
    val padding: Int = run {
        var p = 0
        if (adj.clarity != 0f) p = max(p, clarityRadius * 3)
        if (adj.sharpness > 0f) p += sharpenRadius * 3
        if (adj.noiseReduction > 0f) p += denoiseRadius * 3
        if (adj.blur > 0f) p += blurRadius * 3
        p
    }

    val isIdentity: Boolean = state.adjust.isNeutral && state.filter == FilterLook.NONE &&
        curveLuts == null && hsl.isNeutral

    // ---------------------------------------------------------------- public API

    /**
     * Renders the whole image in place. [read] must return rows of the *original*
     * image; the renderer guarantees it never reads a row after writing it.
     */
    fun render(
        width: Int,
        height: Int,
        read: (y: Int, rows: Int, dest: IntArray) -> Unit,
        write: (y: Int, rows: Int, src: IntArray) -> Unit,
        stripRows: Int = 256,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ) {
        if (isIdentity) return
        val pad = padding
        var carry = IntArray(0) // original rows [y0 - padTop, y0) from the previous strip
        var carryRows = 0
        var y0 = 0
        while (y0 < height) {
            if (isCancelled()) throw java.util.concurrent.CancellationException("Render cancelled")
            val y1 = min(height, y0 + stripRows)
            val padTop = carryRows
            val padBottom = min(pad, height - y1)
            val rows = padTop + (y1 - y0) + padBottom
            val buf = IntArray(rows * width)
            if (padTop > 0) System.arraycopy(carry, 0, buf, 0, padTop * width)
            val fresh = IntArray((rows - padTop) * width)
            read(y0, rows - padTop, fresh)
            System.arraycopy(fresh, 0, buf, padTop * width, fresh.size)

            // Keep the originals that the next strip needs as its top padding.
            val nextPadTop = min(pad, y1 - 0).coerceAtMost(rows - padBottom)
            carryRows = min(nextPadTop, rows - padBottom)
            carry = IntArray(carryRows * width)
            System.arraycopy(buf, (rows - padBottom - carryRows) * width, carry, 0, carryRows * width)

            val out = processStrip(buf, width, rows, padTop, padBottom, y0 - padTop, height)
            write(y0, y1 - y0, out)
            onProgress(y1.toFloat() / height)
            y0 = y1
        }
    }

    /** Convenience for small images (previews, tests): processes a full buffer. */
    fun renderFull(pixels: IntArray, width: Int, height: Int): IntArray {
        if (isIdentity) return pixels.copyOf()
        val out = IntArray(pixels.size)
        render(width, height,
            read = { y, rows, dest -> System.arraycopy(pixels, y * width, dest, 0, rows * width) },
            write = { y, rows, src -> System.arraycopy(src, 0, out, y * width, rows * width) },
            stripRows = max(64, height),
        )
        return out
    }

    // ---------------------------------------------------------------- strip processing

    /**
     * @param buf strip including padding rows; [padTop] rows above and [padBottom] below
     *            the rows to output.
     * @param stripTopY image y of buf's first row (for position-dependent effects).
     * @return only the output rows (without padding).
     */
    internal fun processStrip(
        buf: IntArray, width: Int, rows: Int, padTop: Int, padBottom: Int, stripTopY: Int, imageHeight: Int,
    ): IntArray {
        // 1. Point operations on every row (padding included, since neighbours need them).
        val r = FloatArray(buf.size)
        val g = FloatArray(buf.size)
        val b = FloatArray(buf.size)
        parallelRows(rows) { y ->
            val base = y * width
            for (x in 0 until width) {
                val i = base + x
                val p = buf[i]
                val px = pointOps((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                r[i] = px[0]; g[i] = px[1]; b[i] = px[2]
            }
        }

        // 2. Neighbourhood operations.
        if (adj.noiseReduction > 0f) denoise(r, g, b, width, rows)
        if (adj.clarity != 0f) localContrast(r, g, b, width, rows, clarityRadius, adj.clarity * 0.6f, midtoneWeighted = true)
        if (adj.sharpness > 0f) localContrast(r, g, b, width, rows, sharpenRadius, adj.sharpness * 1.4f, midtoneWeighted = false)
        if (adj.blur > 0f) {
            for (plane in arrayOf(r, g, b)) {
                repeat(3) { boxBlur(plane, width, rows, blurRadius) }
            }
        }

        // 3. Position-dependent effects and packing (output rows only).
        val outRows = rows - padTop - padBottom
        val out = IntArray(outRows * width)
        val cx = (width - 1) / 2f
        val cy = (imageHeight - 1) / 2f
        val maxD = sqrt(cx * cx + cy * cy).coerceAtLeast(1f)
        val vig = adj.vignette
        val grain = adj.grain
        parallelRows(outRows) { oy ->
            val y = oy + padTop
            val imageY = stripTopY + y
            for (x in 0 until width) {
                val i = y * width + x
                var rr = r[i]; var gg = g[i]; var bb = b[i]
                if (vig != 0f) {
                    val dx = x - cx
                    val dy = imageY - cy
                    val d = sqrt(dx * dx + dy * dy) / maxD
                    val w = smoothstep(0.35f, 1.05f, d)
                    if (vig > 0) {
                        val f = 1f - vig * 0.85f * w
                        rr *= f; gg *= f; bb *= f
                    } else {
                        val f = -vig * 0.85f * w
                        rr += (1f - rr) * f; gg += (1f - gg) * f; bb += (1f - bb) * f
                    }
                }
                if (grain > 0f) {
                    val n = (hashNoise(x, imageY) - 0.5f) * grain * 0.18f
                    rr += n; gg += n; bb += n
                }
                val a = buf[i] ushr 24
                out[oy * width + x] = (a shl 24) or (to8(rr) shl 16) or (to8(gg) shl 8) or to8(bb)
            }
        }
        return out
    }

    private val tmp = ThreadLocal.withInitial { FloatArray(3) }

    /** All per-pixel colour and tone operations. Returns a thread-local float[3] (0..1). */
    internal fun pointOps(ri: Int, gi: Int, bi: Int): FloatArray {
        val o = tmp.get()
        var r: Float
        var g: Float
        var b: Float
        if (needsLinear) {
            r = encode(rolloff(SRGB_TO_LINEAR[ri] * expGain * rGain))
            g = encode(rolloff(SRGB_TO_LINEAR[gi] * expGain * gGain))
            b = encode(rolloff(SRGB_TO_LINEAR[bi] * expGain * bGain))
        } else {
            r = ri / 255f; g = gi / 255f; b = bi / 255f
        }
        if (!toneIsIdentity) {
            r = tone(r); g = tone(g); b = tone(b)
        }
        if (satFactor != 1f || vib != 0f) {
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            var f = satFactor
            if (vib != 0f) {
                val sat = max(r, max(g, b)) - min(r, min(g, b))
                f *= 1f + vib * (1f - sat).coerceIn(0f, 1f) * (if (vib > 0) 0.9f else 1f)
            }
            r = l + (r - l) * f; g = l + (g - l) * f; b = l + (b - l) * f
        }
        if (!hsl.isNeutral) {
            val res = applyHsl(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
            r = res[0]; g = res[1]; b = res[2]
        }
        val m = matrix
        if (m != null && intensity > 0f) {
            val nr = m[0] * r + m[1] * g + m[2] * b + m[4]
            val ng = m[5] * r + m[6] * g + m[7] * b + m[9]
            val nb = m[10] * r + m[11] * g + m[12] * b + m[14]
            r += (nr - r) * intensity; g += (ng - g) * intensity; b += (nb - b) * intensity
        }
        if (split > 0f) {
            val l = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            look.shadowTint?.let { t ->
                val w = (1f - l) * (1f - l) * split
                r += (t[0] - r) * w * 0.5f; g += (t[1] - g) * w * 0.5f; b += (t[2] - b) * w * 0.5f
            }
            look.highlightTint?.let { t ->
                val w = l * l * split
                r += (r * t[0] - r) * w; g += (g * t[1] - g) * w; b += (b * t[2] - b) * w
            }
        }
        val c = curveLuts
        if (c != null) {
            r = lutLerp(c[0], r); g = lutLerp(c[1], g); b = lutLerp(c[2], b)
        }
        o[0] = r; o[1] = g; o[2] = b
        return o
    }

    private fun tone(v: Float): Float {
        if (v <= 0f) return toneLut[0] + v
        if (v >= 1f) return toneLut[TONE_SIZE - 1] + (v - 1f)
        val f = v * (TONE_SIZE - 1)
        val i = f.toInt()
        val t = f - i
        return toneLut[i] + (toneLut[min(i + 1, TONE_SIZE - 1)] - toneLut[i]) * t
    }

    // ---------------------------------------------------------------- HSL

    private val hslTmp = ThreadLocal.withInitial { FloatArray(3) }

    private fun applyHsl(r: Float, g: Float, b: Float): FloatArray {
        val out = hslTmp.get()
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        if (d < 1e-5f) { out[0] = r; out[1] = g; out[2] = b; return out }
        var h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0) h += 360f
        val l = (mx + mn) / 2f
        var s = if (l > 0f && l < 1f) d / (1f - kotlin.math.abs(2f * l - 1f)) else 0f
        var hueShift = 0f
        var satAdj = 0f
        var lumAdj = 0f
        var totalW = 0f
        for (band in HueBand.entries) {
            val v = hsl.get(band)
            if (v == HslValue()) continue
            val w = bandWeight(h, band.centreDegrees)
            if (w <= 0f) continue
            hueShift += v.hue * 30f * w
            satAdj += v.saturation * w
            lumAdj += v.luminance * w
            totalW += w
        }
        if (totalW == 0f) { out[0] = r; out[1] = g; out[2] = b; return out }
        h = (h + hueShift + 360f) % 360f
        s = (s * (1f + satAdj)).coerceIn(0f, 1f)
        val nl = (l + lumAdj * 0.25f * s).coerceIn(0f, 1f)
        hslToRgb(h, s, nl, out)
        return out
    }

    private fun bandWeight(h: Float, centre: Float): Float {
        var d = kotlin.math.abs(h - centre)
        if (d > 180f) d = 360f - d
        return (1f - d / 45f).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- neighbourhood ops

    /** Unsharp-mask style local contrast on luminance (no colour fringing). */
    private fun localContrast(
        r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int, radius: Int, amount: Float, midtoneWeighted: Boolean,
    ) {
        val lum = FloatArray(r.size)
        parallelRows(h) { y -> for (x in 0 until w) { val i = y * w + x; lum[i] = 0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i] } }
        val blurred = lum.copyOf()
        repeat(if (midtoneWeighted) 2 else 1) { boxBlur(blurred, w, h, radius) }
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                var d = (lum[i] - blurred[i]) * amount
                if (midtoneWeighted) {
                    val l = lum[i].coerceIn(0f, 1f)
                    d *= 4f * l * (1f - l) // protect deep shadows and bright highlights
                }
                r[i] += d; g[i] += d; b[i] += d
            }
        }
    }

    /** Edge-aware smoothing: blends towards a local mean where the image is flat. */
    private fun denoise(r: FloatArray, g: FloatArray, b: FloatArray, w: Int, h: Int) {
        val amount = adj.noiseReduction
        val br = r.copyOf(); val bg = g.copyOf(); val bb = b.copyOf()
        for (p in arrayOf(br, bg, bb)) repeat(2) { boxBlur(p, w, h, denoiseRadius) }
        val threshold = 0.04f + 0.12f * amount
        parallelRows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val diff = kotlin.math.abs(r[i] - br[i]) + kotlin.math.abs(g[i] - bg[i]) + kotlin.math.abs(b[i] - bb[i])
                val wgt = amount * (1f - smoothstep(0f, threshold, diff))
                r[i] += (br[i] - r[i]) * wgt; g[i] += (bg[i] - g[i]) * wgt; b[i] += (bb[i] - b[i]) * wgt
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    companion object {
        const val TONE_SIZE = 1024

        val SRGB_TO_LINEAR = FloatArray(256) { i ->
            val c = i / 255f
            if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }

        private const val ENC_SIZE = 4096
        private val LINEAR_TO_SRGB = FloatArray(ENC_SIZE + 1) { i ->
            val l = i / ENC_SIZE.toFloat()
            if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
        }

        fun encode(l: Float): Float {
            if (l <= 0f) return 0f
            if (l >= 1f) return 1f
            val f = l * ENC_SIZE
            val i = f.toInt()
            return LINEAR_TO_SRGB[i] + (LINEAR_TO_SRGB[i + 1] - LINEAR_TO_SRGB[i]) * (f - i)
        }

        /** Soft highlight shoulder so exposure boosts roll off instead of clipping hard. */
        fun rolloff(x: Float): Float = if (x <= 0.8f) x else 0.8f + 0.2f * (1f - exp(-(x - 0.8f) / 0.2f))

        fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        fun to8(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)

        fun lutLerp(lut: IntArray, v: Float): Float {
            val f = v.coerceIn(0f, 1f) * 255f
            val i = f.toInt().coerceAtMost(254)
            val t = f - i
            return (lut[i] + (lut[i + 1] - lut[i]) * t) / 255f
        }

        fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
            val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
            val hp = h / 60f
            val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
            val (r1, g1, b1) = when (hp.toInt()) {
                0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
            }
            val m = l - c / 2f
            out[0] = r1 + m; out[1] = g1 + m; out[2] = b1 + m
        }

        /** Deterministic per-pixel noise in 0..1. */
        fun hashNoise(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFF) / 65535f
        }

        /**
         * Global tone mapping: brightness, contrast, highlights, shadows, whites, blacks
         * and fade, baked into a monotonic lookup table.
         */
        fun buildToneLut(a: Adjustments, fade: Float): FloatArray {
            val lut = FloatArray(TONE_SIZE)
            val gamma = 2.0.pow(-a.brightness.toDouble() * 0.9).toFloat()
            for (i in 0 until TONE_SIZE) {
                val x = i / (TONE_SIZE - 1f)
                var y = x
                // Blacks / whites move the end points.
                val bp = -a.blacks * 0.12f
                val wp = 1f - a.whites * 0.15f
                y = ((y - bp) / (wp - bp))
                // Brightness: midtone gamma.
                y = if (y > 0f) y.pow(gamma) else y
                // Shadows / highlights: smooth regional lifts.
                val sw = 1f - smoothstep(0f, 0.55f, y)
                y += a.shadows * 0.28f * sw * y.coerceIn(0f, 1f).let { sqrt(it) }
                val hw = smoothstep(0.45f, 1f, y)
                y += a.highlights * 0.25f * hw * (1f - (y - 1f).coerceAtLeast(0f))
                // Contrast: S-curve around mid-grey.
                if (a.contrast != 0f) {
                    val c = a.contrast
                    val yc = y.coerceIn(0f, 1f)
                    y = if (c > 0) {
                        val s = yc * yc * (3f - 2f * yc)
                        y + (s - yc) * c * 1.1f
                    } else {
                        y + (0.5f - y) * (-c) * 0.5f
                    }
                }
                // Fade lifts the black point (film look).
                if (fade > 0f) y = fade + y * (1f - fade)
                lut[i] = y
            }
            // Enforce monotonic so no tones invert.
            for (i in 1 until TONE_SIZE) if (lut[i] < lut[i - 1]) lut[i] = lut[i - 1]
            return lut
        }

        private const val FIXED_ONE = 65536f

        /**
         * In-place separable box blur, O(n) per pass regardless of radius.
         *
         * Uses exact fixed-point integer running sums: a floating-point running sum
         * accumulates rounding error that depends on where the window started, which
         * would make strip-rendered output differ (by 1 level) from whole-image output.
         */
        fun boxBlur(p: FloatArray, w: Int, h: Int, radius: Int) {
            if (radius <= 0 || w == 0 || h == 0) return
            val q = IntArray(p.size)
            parallelRows(h) { y -> val base = y * w; for (x in 0 until w) q[base + x] = Math.round(p[base + x] * FIXED_ONE) }
            val tmp = IntArray(p.size)
            // Horizontal
            parallelRows(h) { y ->
                val base = y * w
                val r = min(radius, w - 1)
                val n = 2L * r + 1
                var sum = 0L
                for (x in -r..r) sum += q[base + x.coerceIn(0, w - 1)]
                for (x in 0 until w) {
                    tmp[base + x] = Math.floorDiv(sum + n / 2, n).toInt()
                    sum += q[base + min(w - 1, x + r + 1)] - q[base + max(0, x - r)]
                }
            }
            // Vertical
            parallelRows(w) { x ->
                val r = min(radius, h - 1)
                val n = 2L * r + 1
                var sum = 0L
                for (y in -r..r) sum += tmp[y.coerceIn(0, h - 1) * w + x]
                for (y in 0 until h) {
                    p[y * w + x] = Math.floorDiv(sum + n / 2, n) / FIXED_ONE
                    sum += tmp[min(h - 1, y + r + 1) * w + x] - tmp[max(0, y - r) * w + x]
                }
            }
        }

        /** Runs [block] for 0 until n, in parallel for large n. */
        inline fun parallelRows(n: Int, crossinline block: (Int) -> Unit) {
            if (n < 32) for (i in 0 until n) block(i)
            else IntStream.range(0, n).parallel().forEach { block(it) }
        }
    }
}
