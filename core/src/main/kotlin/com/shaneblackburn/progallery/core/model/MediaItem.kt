package com.shaneblackburn.progallery.core.model

/**
 * Platform-independent description of one photo or video.
 *
 * The Android layer builds these from MediaStore rows. Nothing in here depends on
 * Android, so all sorting, filtering, grouping and search logic can be unit-tested
 * on a plain JVM.
 *
 * All timestamps are epoch milliseconds (UTC).
 */
data class MediaItem(
    val id: Long,
    /** content:// URI as a string (kept as String to stay Android-free). */
    val uri: String,
    val name: String,
    val mimeType: String,
    val type: MediaType,
    val bucketId: Long,
    val bucketName: String,
    /** MediaStore RELATIVE_PATH, e.g. "DCIM/Camera/". May be empty on old rows. */
    val relativePath: String,
    val dateTaken: Long,
    val dateModified: Long,
    val dateAdded: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** Video duration in milliseconds; 0 for photos. */
    val durationMs: Long = 0L,
    /** EXIF orientation in degrees (0, 90, 180, 270) as reported by MediaStore. */
    val orientation: Int = 0,
    val isFavorite: Boolean = false,
    val isTrashed: Boolean = false,
    /** Epoch millis after which the system will purge a trashed item (0 if unknown). */
    val trashExpiresAt: Long = 0L,
) {
    val isVideo: Boolean get() = type == MediaType.VIDEO
    val isImage: Boolean get() = type == MediaType.IMAGE

    /** Extension in lower case without the dot, or "" when the name has none. */
    val extension: String
        get() {
            val dot = name.lastIndexOf('.')
            return if (dot in 1 until name.length - 1) name.substring(dot + 1).lowercase() else ""
        }

    /** Best "when was this captured" timestamp: date taken, else modified, else added. */
    val effectiveDate: Long
        get() = when {
            dateTaken > 0 -> dateTaken
            dateModified > 0 -> dateModified
            else -> dateAdded
        }

    /** Width/height after applying EXIF orientation. */
    val displayWidth: Int get() = if (orientation % 180 == 90) height else width
    val displayHeight: Int get() = if (orientation % 180 == 90) width else height

    val megapixels: Double get() = (width.toLong() * height.toLong()) / 1_000_000.0

    val isGif: Boolean get() = mimeType.equals("image/gif", ignoreCase = true) || extension == "gif"

    val isAnimatedWebpCandidate: Boolean get() = mimeType.equals("image/webp", ignoreCase = true)

    val isRaw: Boolean
        get() = extension in RAW_EXTENSIONS ||
            mimeType.equals("image/x-adobe-dng", ignoreCase = true) ||
            mimeType.startsWith("image/x-") && mimeType.lowercase().let { m -> RAW_MIME_HINTS.any { m.contains(it) } }

    /**
     * Cheap name-based hint that a file is a Google/Samsung motion photo. The viewer
     * confirms by reading XMP metadata before offering motion playback.
     */
    val isMotionPhotoCandidate: Boolean
        get() = isImage && (name.startsWith("MVIMG_") || name.contains(".MP.") || name.endsWith(".MP.jpg", true))

    val category: MediaCategory get() = MediaCategory.classify(this)

    companion object {
        val RAW_EXTENSIONS = setOf("dng", "cr2", "cr3", "nef", "arw", "orf", "rw2", "raf", "srw", "pef")
        private val RAW_MIME_HINTS = listOf("dng", "canon", "nikon", "sony", "olympus", "panasonic", "fuji", "raw")
    }
}

enum class MediaType { IMAGE, VIDEO }

/** Smart categories derived from where a file lives and what it is. */
enum class MediaCategory {
    CAMERA, SCREENSHOT, SCREEN_RECORDING, DOWNLOAD, MESSAGING, OTHER;

    companion object {
        fun classify(item: MediaItem): MediaCategory {
            val path = item.relativePath.lowercase()
            val bucket = item.bucketName.lowercase()
            return when {
                "screenrecord" in path || "screen recording" in bucket || "screenrecorder" in bucket ->
                    SCREEN_RECORDING
                "screenshot" in path || "screenshot" in bucket -> SCREENSHOT
                path.startsWith("download") || bucket == "download" || bucket == "downloads" -> DOWNLOAD
                path.startsWith("dcim/camera") || bucket == "camera" -> CAMERA
                listOf("whatsapp", "telegram", "signal", "messenger", "messages").any { it in path || it in bucket } ->
                    MESSAGING
                else -> OTHER
            }
        }
    }
}
