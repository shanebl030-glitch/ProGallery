package com.shaneblackburn.progallery.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log

/** What a video's track looks like to the decoder — used to diagnose preview/export failures. */
data class VideoInfo(
    val mime: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val rotationDegrees: Int,
    val bitDepth: Int,        // 8 or 10 (best effort)
    val hdr: String,          // "SDR", "HLG", "PQ/HDR10", or "unknown"
    val durationMs: Long,
) {
    val isHdr: Boolean get() = hdr == "HLG" || hdr == "PQ/HDR10"
    val is10Bit: Boolean get() = bitDepth >= 10

    /** One-line human summary, e.g. "HEVC 1080×2340 30fps 10-bit HDR10". */
    fun summary(): String = buildString {
        append(codecLabel(mime))
        append(' ').append(width).append('×').append(height)
        if (frameRate > 0) append(' ').append(frameRate).append("fps")
        if (bitDepth > 0) append(' ').append(bitDepth).append("-bit")
        if (hdr != "SDR" && hdr != "unknown") append(' ').append(hdr)
    }

    companion object {
        private fun codecLabel(mime: String) = when {
            mime.contains("hevc", true) || mime.contains("h265", true) -> "HEVC"
            mime.contains("avc", true) || mime.contains("h264", true) -> "H.264"
            mime.contains("av01", true) || mime.contains("av1", true) -> "AV1"
            mime.contains("vp9", true) -> "VP9"
            mime.contains("dolby", true) || mime.contains("dvhe", true) -> "Dolby Vision"
            else -> mime.substringAfter('/')
        }
    }
}

/** Reads a video file's format with [MediaExtractor]; never throws (returns null on failure). */
object VideoDiagnostics {
    private const val TAG = "VideoDiagnostics"

    fun read(context: Context, uri: Uri): VideoInfo? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { fmt = f; break }
            }
            val f = fmt ?: return null
            fun intOrNull(key: String): Int? = if (f.containsKey(key)) runCatching { f.getInteger(key) }.getOrNull() else null
            val mime = f.getString(MediaFormat.KEY_MIME) ?: "video/unknown"
            val transfer = intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
            val hdr = when (transfer) {
                MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
                MediaFormat.COLOR_TRANSFER_ST2084 -> "PQ/HDR10"
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO, MediaFormat.COLOR_TRANSFER_LINEAR -> "SDR"
                null -> "unknown"
                else -> "SDR"
            }
            val bitDepth = guessBitDepth(mime, intOrNull(MediaFormat.KEY_PROFILE), hdr)
            VideoInfo(
                mime = mime,
                width = intOrNull(MediaFormat.KEY_WIDTH) ?: 0,
                height = intOrNull(MediaFormat.KEY_HEIGHT) ?: 0,
                frameRate = intOrNull(MediaFormat.KEY_FRAME_RATE) ?: 0,
                rotationDegrees = intOrNull(MediaFormat.KEY_ROTATION) ?: 0,
                bitDepth = bitDepth,
                hdr = hdr,
                durationMs = (if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L) / 1000,
            ).also { Log.i(TAG, "format for $uri: ${it.summary()} (rotation ${it.rotationDegrees}°)") }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read format for $uri", e)
            null
        } finally {
            runCatching { ex.release() }
        }
    }

    // HEVC Main10 profile constants (MediaCodecInfo.CodecProfileLevel): HEVCProfileMain10 = 2,
    // HEVCProfileMain10HDR10 = 0x1000, HEVCProfileMain10HDR10Plus = 0x4000.
    private fun guessBitDepth(mime: String, profile: Int?, hdr: String): Int {
        if (hdr == "HLG" || hdr == "PQ/HDR10") return 10
        if (mime.contains("hevc", true) && profile != null && (profile == 2 || profile == 0x1000 || profile == 0x4000)) return 10
        if (mime.contains("av01", true) && profile != null && profile != 0) return 10 // AV1 Main 10 ~ profile!=Main8
        return if (profile == null) 0 else 8
    }
}
