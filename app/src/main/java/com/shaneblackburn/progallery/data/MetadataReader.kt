package com.shaneblackburn.progallery.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.shaneblackburn.progallery.core.format.Formatters
import com.shaneblackburn.progallery.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One labelled row in the info sheet. */
data class InfoRow(val label: String, val value: String)

data class InfoSection(val title: String, val rows: List<InfoRow>)

data class MediaDetails(
    val sections: List<InfoSection>,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isMotionPhoto: Boolean = false,
)

/**
 * Reads EXIF (photos) and container metadata (videos) for the details sheet.
 * GPS is only available when ACCESS_MEDIA_LOCATION was granted; otherwise Android
 * redacts it and we simply show no location.
 */
class MetadataReader(private val context: Context) {

    suspend fun read(item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        val file = InfoSection(
            "File",
            listOfNotNull(
                InfoRow("Name", item.name),
                InfoRow("Folder", item.relativePath.ifBlank { item.bucketName }),
                InfoRow("Size", Formatters.fileSize(item.sizeBytes)),
                InfoRow("Type", item.mimeType),
                InfoRow("Resolution", Formatters.resolution(item.displayWidth, item.displayHeight)),
                if (item.isVideo) InfoRow("Duration", Formatters.duration(item.durationMs)) else null,
                InfoRow("Date", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.effectiveDate))),
                InfoRow("Modified", java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.dateModified))),
            ),
        )
        try {
            if (item.isVideo) readVideo(item, file) else readImage(item, file)
        } catch (e: Exception) {
            Log.w("MetadataReader", "metadata read failed for ${item.name}", e)
            MediaDetails(listOf(file))
        }
    }

    private fun originalUri(item: MediaItem): Uri {
        val uri = Uri.parse(item.uri)
        // setRequireOriginal un-redacts GPS when ACCESS_MEDIA_LOCATION is granted.
        return runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
    }

    private fun readImage(item: MediaItem, file: InfoSection): MediaDetails {
        val resolver = context.contentResolver
        val stream = runCatching { resolver.openInputStream(originalUri(item)) }.getOrNull()
            ?: resolver.openInputStream(Uri.parse(item.uri))
            ?: return MediaDetails(listOf(file))
        stream.use { input ->
            val exif = ExifInterface(input)
            val camera = buildList {
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                val device = listOfNotNull(make, model).joinToString(" ").let { s ->
                    // Many models already include the make ("Google Pixel 8").
                    if (make != null && model != null && model.startsWith(make, true)) model else s
                }
                if (device.isNotBlank()) add(InfoRow("Camera", device))
                exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { add(InfoRow("Lens", it)) }
                exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }
                    ?.let { add(InfoRow("Aperture", Formatters.aperture(it))) }
                exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }
                    ?.let { add(InfoRow("Shutter", Formatters.exposureTime(it))) }
                (exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))?.let { add(InfoRow("ISO", it)) }
                exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }
                    ?.let { add(InfoRow("Focal length", Formatters.focalLength(it))) }
                exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 }
                    ?.let { add(InfoRow("35mm equivalent", "$it mm")) }
                exif.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 }
                    ?.let { add(InfoRow("Flash", if (it and 1 == 1) "Fired" else "Off")) }
                exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1).takeIf { it >= 0 }
                    ?.let { add(InfoRow("White balance", if (it == 0) "Auto" else "Manual")) }
                exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.let { add(InfoRow("Software", it)) }
            }
            val latLong = exif.latLong
            val location = if (latLong != null) {
                InfoSection("Location", listOf(
                    InfoRow("Coordinates", String.format(java.util.Locale.ROOT, "%.5f, %.5f", latLong[0], latLong[1])),
                ) + (exif.getAltitude(Double.NaN).takeIf { !it.isNaN() }
                    ?.let { listOf(InfoRow("Altitude", String.format(java.util.Locale.ROOT, "%.0f m", it))) } ?: emptyList()))
            } else null
            val xmp = exif.getAttribute(ExifInterface.TAG_XMP).orEmpty()
            val motion = xmp.contains("MotionPhoto=\"1\"") || xmp.contains("GCamera:MicroVideo=\"1\"") ||
                xmp.contains("Camera:MotionPhoto") || xmp.contains("MicroVideoOffset")
            return MediaDetails(
                sections = listOfNotNull(file, camera.takeIf { it.isNotEmpty() }?.let { InfoSection("Camera", it) }, location),
                latitude = latLong?.get(0),
                longitude = latLong?.get(1),
                isMotionPhoto = motion,
            )
        }
    }

    private fun readVideo(item: MediaItem, file: InfoSection): MediaDetails {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, Uri.parse(item.uri))
            fun meta(key: Int) = runCatching { mmr.extractMetadata(key) }.getOrNull()
            val rows = buildList {
                meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                    ?.let { add(InfoRow("Bitrate", Formatters.bitrate(it))) }
                val frames = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                if (frames != null && item.durationMs > 0) {
                    val fps = frames * 1000.0 / item.durationMs
                    add(InfoRow("Frame rate", String.format(java.util.Locale.ROOT, "%.2f fps", fps)))
                }
                meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.let {
                    add(InfoRow("Capture rate", String.format(java.util.Locale.ROOT, "%.0f fps", it)))
                }
                meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.takeIf { it != "0" }
                    ?.let { add(InfoRow("Rotation", "$it°")) }
                add(InfoRow("Audio", if (meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") "Yes" else "No"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    meta(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.let { t ->
                        // 6 = ST2084 (HDR10/PQ), 7 = HLG
                        if (t == "6" || t == "7") add(InfoRow("HDR", if (t == "6") "HDR10 / PQ" else "HLG"))
                    }
                }
            }
            val loc = meta(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val (lat, lon) = parseIso6709(loc)
            return MediaDetails(
                sections = listOfNotNull(file, InfoSection("Video", rows),
                    if (lat != null && lon != null) InfoSection("Location",
                        listOf(InfoRow("Coordinates", String.format(java.util.Locale.ROOT, "%.5f, %.5f", lat, lon)))) else null),
                latitude = lat, longitude = lon,
            )
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Parses "+37.4219-122.0840/" style ISO-6709 strings. */
    private fun parseIso6709(s: String?): Pair<Double?, Double?> {
        if (s.isNullOrBlank()) return null to null
        val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s) ?: return null to null
        return m.groupValues[1].toDoubleOrNull() to m.groupValues[2].toDoubleOrNull()
    }
}
