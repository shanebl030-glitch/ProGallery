package com.shaneblackburn.progallery.ui.components

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.pxOrElse
import com.shaneblackburn.progallery.core.model.MediaItem
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Coil model for a grid thumbnail. Uses [android.content.ContentResolver.loadThumbnail],
 * which serves MediaStore's pre-generated thumbnails (and the video poster frame) —
 * far faster than decoding a 12 MP JPEG for every cell while flinging.
 *
 * [version] (date modified + size) invalidates caches when a file is edited in place.
 */
data class MediaThumb(val uri: String, val version: Long) {
    constructor(item: MediaItem) : this(item.uri, item.dateModified xor item.sizeBytes)
}

class MediaThumbKeyer : Keyer<MediaThumb> {
    override fun key(data: MediaThumb, options: Options): String = "thumb:${data.uri}:${data.version}"
}

class MediaThumbFetcher(
    private val data: MediaThumb,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val w = options.size.width.pxOrElse { DEFAULT_PX }.coerceIn(64, MAX_PX)
        val h = options.size.height.pxOrElse { DEFAULT_PX }.coerceIn(64, MAX_PX)
        val signal = CancellationSignal()
        val bitmap = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { signal.cancel() }
            try {
                cont.resumeWith(Result.success(
                    options.context.contentResolver.loadThumbnail(Uri.parse(data.uri), Size(w, h), signal),
                ))
            } catch (e: Exception) {
                cont.resumeWith(Result.failure(e))
            }
        }
        DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<MediaThumb> {
        override fun create(data: MediaThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_PX = 320
        const val MAX_PX = 1024
    }
}
