package com.shaneblackburn.progallery.core.edit

/**
 * Tone curve with a master (RGB) curve and optional per-channel curves. Each curve is a
 * list of control points in 0..1 interpolated with a monotone cubic (Fritsch–Carlson),
 * so curves never overshoot or invert tones.
 */
data class ToneCurve(
    val master: List<CurvePoint> = IDENTITY_POINTS,
    val red: List<CurvePoint> = IDENTITY_POINTS,
    val green: List<CurvePoint> = IDENTITY_POINTS,
    val blue: List<CurvePoint> = IDENTITY_POINTS,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    /** 256-entry lookup tables (r, g, b) combining channel curve then master curve. */
    fun luts(): Array<IntArray> {
        val m = lut(master)
        return arrayOf(red, green, blue).map { ch ->
            val c = lut(ch)
            IntArray(256) { m[c[it]] }
        }.toTypedArray()
    }

    companion object {
        val IDENTITY_POINTS = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        val IDENTITY = ToneCurve()

        /** Gentle S-curve preset. */
        fun sCurve(amount: Float = 0.5f): List<CurvePoint> {
            val d = 0.08f * amount.coerceIn(0f, 1f) * 2f
            return listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.25f - d), CurvePoint(0.75f, 0.75f + d), CurvePoint(1f, 1f))
        }

        fun lut(points: List<CurvePoint>): IntArray {
            val pts = sanitise(points)
            val spline = MonotoneCubic(pts.map { it.x }.toFloatArray(), pts.map { it.y }.toFloatArray())
            return IntArray(256) { i -> Math.round(spline.eval(i / 255f).coerceIn(0f, 1f) * 255f) }
        }

        /** Sorts, clamps and de-duplicates x so the spline is always well defined. */
        fun sanitise(points: List<CurvePoint>): List<CurvePoint> {
            val sorted = points.map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                .sortedBy { it.x }
            val out = ArrayList<CurvePoint>()
            for (p in sorted) {
                if (out.isNotEmpty() && p.x - out.last().x < 0.01f) out[out.lastIndex] = p else out += p
            }
            if (out.isEmpty() || out.first().x > 0f) out.add(0, CurvePoint(0f, out.firstOrNull()?.y ?: 0f))
            if (out.last().x < 1f) out.add(CurvePoint(1f, out.last().y))
            return out
        }
    }
}

data class CurvePoint(val x: Float, val y: Float)

/** Monotone cubic Hermite interpolation (Fritsch–Carlson). */
class MonotoneCubic(private val xs: FloatArray, private val ys: FloatArray) {
    private val m = FloatArray(xs.size)

    init {
        require(xs.size >= 2 && xs.size == ys.size)
        val n = xs.size
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / kotlin.math.sqrt(s)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
    }

    fun eval(x: Float): Float {
        if (x <= xs[0]) return ys[0]
        if (x >= xs.last()) return ys.last()
        var i = 0
        while (i < xs.size - 2 && x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val t = (x - xs[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h * m[i + 1]
    }
}
