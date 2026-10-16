package com.shaneblackburn.progallery.core.format

import java.util.Locale
import kotlin.math.abs

object Formatters {

    /** SI units (1 kB = 1000 B), matching Android's own Formatter and Files app. */
    fun fileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 0) return "—"
        if (bytes < 1000) return "$bytes B"
        val units = arrayOf("kB", "MB", "GB", "TB")
        var value = bytes / 1000.0
        var unit = 0
        while (value >= 999.95 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val pattern = if (value >= 100) "%.0f %s" else if (value >= 10) "%.1f %s" else "%.2f %s"
        return String.format(locale, pattern, value, units[unit])
    }

    /** 0:07, 4:05, 1:02:03. Negative values are clamped to zero. */
    fun duration(ms: Long): String {
        val totalSec = (if (ms < 0) 0 else ms) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Precise timestamp for editors: 1:02.35 (minutes:seconds.centiseconds). */
    fun timecode(ms: Long): String {
        val safe = if (ms < 0) 0 else ms
        val totalSec = safe / 1000
        val cs = (safe % 1000) / 10
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.ROOT, "%d:%02d.%02d", m, s, cs)
    }

    fun resolution(width: Int, height: Int, locale: Locale = Locale.getDefault()): String {
        if (width <= 0 || height <= 0) return "Unknown"
        val mp = width.toLong() * height / 1_000_000.0
        return if (mp >= 0.1) String.format(locale, "%d × %d (%.1f MP)", width, height, mp)
        else "$width × $height"
    }

    /** "1/250 s" style exposure time from seconds. */
    fun exposureTime(seconds: Double): String {
        if (seconds <= 0) return "—"
        return if (seconds >= 1) {
            if (abs(seconds - seconds.toLong()) < 0.05) "${seconds.toLong()} s" else String.format(Locale.ROOT, "%.1f s", seconds)
        } else {
            "1/${Math.round(1.0 / seconds)} s"
        }
    }

    fun aperture(fNumber: Double): String = if (fNumber > 0) String.format(Locale.ROOT, "ƒ/%.1f", fNumber) else "—"

    fun focalLength(mm: Double): String = if (mm > 0) String.format(Locale.ROOT, "%.1f mm", mm).replace(".0 mm", " mm") else "—"

    fun bitrate(bitsPerSecond: Long): String = when {
        bitsPerSecond <= 0 -> "—"
        bitsPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        else -> String.format(Locale.ROOT, "%d kbps", bitsPerSecond / 1000)
    }

    fun count(n: Int, singular: String, plural: String = singular + "s"): String =
        "$n ${if (n == 1) singular else plural}"
}
