package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.model.MediaType
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object TestData {
    val ZONE: ZoneId = ZoneOffset.ofHours(13)

    fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZONE).toInstant().toEpochMilli()

    fun item(
        id: Long,
        name: String = "IMG_$id.jpg",
        type: MediaType = MediaType.IMAGE,
        mime: String = if (type == MediaType.IMAGE) "image/jpeg" else "video/mp4",
        bucketId: Long = 1,
        bucket: String = "Camera",
        path: String = "DCIM/Camera/",
        taken: Long = at(2026, 10, 1),
        added: Long = taken,
        modified: Long = taken,
        size: Long = 1_000_000,
        w: Int = 4000,
        h: Int = 3000,
        duration: Long = 0,
        fav: Boolean = false,
        trashed: Boolean = false,
        orientation: Int = 0,
    ) = MediaItem(
        id = id, uri = "content://media/external/file/$id", name = name, mimeType = mime, type = type,
        bucketId = bucketId, bucketName = bucket, relativePath = path, dateTaken = taken, dateModified = modified,
        dateAdded = added, sizeBytes = size, width = w, height = h, durationMs = duration, orientation = orientation,
        isFavorite = fav, isTrashed = trashed,
    )
}
