package com.shaneblackburn.progallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Reads photos and videos from MediaStore (all external volumes, including SD cards).
 *
 * Results are emitted as a full snapshot whenever MediaStore changes. A 100k-item
 * library is a few MB of [MediaItem]s, which is cheaper and far simpler than paging
 * for a gallery that needs global sort, search and grouping.
 */
class MediaRepository(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection: Array<String> = buildList {
        add(MediaStore.Files.FileColumns._ID)
        add(MediaStore.Files.FileColumns.DISPLAY_NAME)
        add(MediaStore.Files.FileColumns.MIME_TYPE)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        add(MediaStore.MediaColumns.BUCKET_ID)
        add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        add(MediaStore.MediaColumns.RELATIVE_PATH)
        add(MediaStore.MediaColumns.DATE_TAKEN)
        add(MediaStore.MediaColumns.DATE_MODIFIED)
        add(MediaStore.MediaColumns.DATE_ADDED)
        add(MediaStore.MediaColumns.SIZE)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
        add(MediaStore.MediaColumns.DURATION)
        add(MediaStore.MediaColumns.ORIENTATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(MediaStore.MediaColumns.IS_TRASHED)
            add(MediaStore.MediaColumns.DATE_EXPIRES)
        }
    }.toTypedArray()

    private val selection =
        "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" +
            " AND ${MediaStore.MediaColumns.SIZE} > 0"

    /** Emits the full library now and again after every MediaStore change (debounced). */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun observeAll(includeTrashed: Boolean = true): Flow<LoadResult> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(filesUri, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .conflate()
        .debounce(300)
        .let { triggers ->
            kotlinx.coroutines.flow.flow {
                triggers.collect { emit(load(includeTrashed)) }
            }
        }
        .flowOn(Dispatchers.IO)

    suspend fun loadOnce(includeTrashed: Boolean = true): LoadResult = withContext(Dispatchers.IO) { load(includeTrashed) }

    private fun load(includeTrashed: Boolean): LoadResult {
        val started = System.nanoTime()
        return try {
            val cursor = query(includeTrashed) ?: return LoadResult.Error("MediaStore returned no data")
            val items = cursor.use { readAll(it) }
            Log.i(TAG, "Loaded ${items.size} items in ${(System.nanoTime() - started) / 1_000_000} ms")
            LoadResult.Success(items)
        } catch (e: SecurityException) {
            LoadResult.PermissionMissing
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query failed", e)
            LoadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun query(includeTrashed: Boolean): Cursor? {
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val args = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                if (includeTrashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(filesUri, projection, args, null)
        } else {
            resolver.query(filesUri, projection, selection, null, sortOrder)
        }
    }

    private fun readAll(c: Cursor): List<MediaItem> {
        val iId = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val iName = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val iMime = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
        val iType = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        val iBucketName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val iModified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val iOrient = c.getColumnIndexOrThrow(MediaStore.MediaColumns.ORIENTATION)
        val iTrashed = c.getColumnIndex("is_trashed")
        val iExpires = c.getColumnIndex("date_expires")

        val out = ArrayList<MediaItem>(c.count)
        while (c.moveToNext()) {
            val id = c.getLong(iId)
            val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val name = c.getString(iName) ?: "Untitled"
            out += MediaItem(
                id = id,
                uri = contentUri(id, isVideo).toString(),
                name = name,
                mimeType = c.getString(iMime) ?: guessMime(name, isVideo),
                type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                bucketId = c.getLong(iBucket),
                bucketName = c.getString(iBucketName) ?: "",
                relativePath = c.getString(iPath) ?: "",
                dateTaken = c.getLong(iTaken),
                dateModified = c.getLong(iModified) * 1000L, // seconds -> ms
                dateAdded = c.getLong(iAdded) * 1000L,
                sizeBytes = c.getLong(iSize),
                width = c.getInt(iW),
                height = c.getInt(iH),
                durationMs = if (isVideo) c.getLong(iDur) else 0L,
                orientation = c.getInt(iOrient),
                isFavorite = false, // app-managed; merged in by the ViewModel
                isTrashed = iTrashed >= 0 && c.getInt(iTrashed) == 1,
                trashExpiresAt = if (iExpires >= 0) c.getLong(iExpires) * 1000L else 0L,
            )
        }
        return out
    }

    companion object {
        private const val TAG = "MediaRepository"

        fun contentUri(id: Long, isVideo: Boolean): Uri = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            id,
        )

        private fun guessMime(name: String, isVideo: Boolean): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (isVideo) "video/*" else "image/*"
        }
    }
}

sealed interface LoadResult {
    data class Success(val items: List<MediaItem>) : LoadResult
    data object PermissionMissing : LoadResult
    data class Error(val message: String) : LoadResult
}
