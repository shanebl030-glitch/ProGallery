package com.shaneblackburn.progallery.editor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportFormat(val label: String, val mime: String, val ext: String, val compress: Bitmap.CompressFormat) {
    JPEG("JPEG", "image/jpeg", "jpg", Bitmap.CompressFormat.JPEG),
    PNG("PNG", "image/png", "png", Bitmap.CompressFormat.PNG),
    WEBP("WebP", "image/webp", "webp",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG,
    /** 1..100, ignored for PNG. */
    val quality: Int = 95,
    /** Longest side in pixels, or 0 for full resolution. */
    val maxSide: Int = 0,
    val keepMetadata: Boolean = true,
    /** Strip GPS even when keeping other metadata. */
    val removeLocation: Boolean = false,
)

/**
 * Writes an edited bitmap as a new MediaStore item (default) or over the original.
 *
 * New items are created with IS_PENDING=1 and only published after a complete write,
 * so a crash or cancellation never leaves a half-written photo in the gallery.
 */
class EditSaver(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Saves a copy next to the original. Returns the new item's URI. */
    suspend fun saveCopy(original: MediaItem, bitmap: Bitmap, options: ExportOptions): Uri = withContext(Dispatchers.IO) {
        val folder = FileNames.normaliseRelativePath(original.relativePath).ifEmpty { "Pictures/ProGallery/" }
        val name = FileNames.editedCopyName(original.name, existingNames(folder), options.format.ext)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, options.format.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (original.dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, original.dateTaken)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            // Some folders (e.g. Download/ from another app) refuse new images; fall back.
            Log.w(TAG, "insert into $folder failed, using Pictures/ProGallery", e)
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ProGallery/")
            resolver.insert(collection, values)
        } ?: error("Couldn't create the new file")
        try {
            writeTo(uri, original, bitmap, options)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /**
     * Overwrites the original file in place (caller must already hold write consent on
     * Android 11+). Encodes to a temp file first, so a failed encode can't corrupt it.
     */
    suspend fun replaceOriginal(original: MediaItem, bitmap: Bitmap, options: ExportOptions) = withContext(Dispatchers.IO) {
        val format = formatFor(original) ?: options.format
        val temp = File.createTempFile("edit", ".${format.ext}", context.cacheDir)
        try {
            temp.outputStream().use { out ->
                check(bitmap.compress(format.compress, options.quality, out)) { "Encoding failed" }
            }
            if (options.keepMetadata && format != ExportFormat.PNG) copyExif(Uri.parse(original.uri), temp, bitmap, options)
            val uri = Uri.parse(original.uri)
            resolver.openOutputStream(uri, "wt")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: error("Couldn't open the original for writing")
            // Ask MediaStore to rescan dimensions and size.
            runCatching {
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.WIDTH, bitmap.width)
                    put(MediaStore.MediaColumns.HEIGHT, bitmap.height)
                    put(MediaStore.MediaColumns.ORIENTATION, 0)
                }, null, null)
            }
        } finally {
            temp.delete()
        }
    }

    /** Format matching the original's MIME, so replacing keeps the file type. */
    fun formatFor(item: MediaItem): ExportFormat? = when (item.mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> ExportFormat.JPEG
        "image/png" -> ExportFormat.PNG
        "image/webp" -> ExportFormat.WEBP
        else -> null
    }

    /** Whether "replace original" is possible without changing the file's format. */
    fun canReplace(item: MediaItem): Boolean = formatFor(item) != null

    private fun writeTo(uri: Uri, original: MediaItem, bitmap: Bitmap, options: ExportOptions) {
        val temp = File.createTempFile("edit", ".${options.format.ext}", context.cacheDir)
        try {
            temp.outputStream().use { out ->
                check(bitmap.compress(options.format.compress, options.quality, out)) { "Encoding failed" }
            }
            if (options.keepMetadata && options.format == ExportFormat.JPEG) {
                copyExif(Uri.parse(original.uri), temp, bitmap, options)
            }
            resolver.openOutputStream(uri, "w")?.use { out -> temp.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: error("Couldn't write the new file")
        } finally {
            temp.delete()
        }
    }

    /**
     * Copies useful EXIF from the original: dates, camera, exposure, GPS (unless removed).
     * Orientation is reset because the edit already rendered the image upright.
     */
    private fun copyExif(from: Uri, to: File, bitmap: Bitmap, options: ExportOptions) {
        try {
            val src = resolver.openInputStream(from)?.use { ExifInterface(it) } ?: return
            val dst = ExifInterface(to.absolutePath)
            val tags = buildList {
                addAll(listOf(
                    ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
                    ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
                    ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
                    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
                    ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE,
                    ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
                ))
                if (!options.removeLocation) addAll(listOf(
                    ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                    ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
                ))
            }
            for (t in tags) src.getAttribute(t)?.let { dst.setAttribute(t, it) }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, bitmap.width.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, bitmap.height.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, bitmap.width.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, bitmap.height.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "ProGallery")
            dst.saveAttributes()
        } catch (e: Exception) {
            // Metadata is best-effort; the edited image itself is already safely written.
            Log.w(TAG, "EXIF copy failed", e)
        }
    }

    private fun existingNames(relativePath: String): Set<String> {
        val out = HashSet<String>()
        runCatching {
            resolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(out::add) }
        }
        return out
    }

    private companion object { const val TAG = "EditSaver" }
}
