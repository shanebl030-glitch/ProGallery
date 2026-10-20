package com.shaneblackburn.progallery.data

import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.util.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Write operations on shared media. On Android 11+ every change to a file the app
 * didn't create needs a one-tap system consent dialog; these methods return
 * [ActionResult.NeedsConsent] carrying the [IntentSender] for that dialog. After the
 * user approves, the system performs trash/restore/delete itself; for rename/move the
 * caller invokes [ActionResult.NeedsConsent.afterGrant].
 */
class MediaActions(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    val supportsTrash: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Moves items to the system trash (auto-deleted by Android after ~30 days). */
    fun trash(items: List<MediaItem>): ActionResult {
        if (items.isEmpty()) return ActionResult.Done(0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActionResult.NeedsConsent(MediaStore.createTrashRequest(resolver, items.uris(), true).intentSender)
        } else {
            deleteLegacy(items)
        }
    }

    fun restore(items: List<MediaItem>): ActionResult {
        if (items.isEmpty()) return ActionResult.Done(0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActionResult.NeedsConsent(MediaStore.createTrashRequest(resolver, items.uris(), false).intentSender)
        } else {
            ActionResult.Failed("Recently deleted needs Android 11 or newer")
        }
    }

    /** Permanently deletes items (bypassing trash). */
    fun deleteForever(items: List<MediaItem>): ActionResult {
        if (items.isEmpty()) return ActionResult.Done(0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActionResult.NeedsConsent(MediaStore.createDeleteRequest(resolver, items.uris()).intentSender)
        } else {
            deleteLegacy(items)
        }
    }

    /**
     * Android 10: delete directly; for files owned by another app the platform throws a
     * RecoverableSecurityException whose consent covers just that one file.
     */
    private fun deleteLegacy(items: List<MediaItem>): ActionResult {
        var deleted = 0
        for ((index, item) in items.withIndex()) {
            try {
                deleted += resolver.delete(Uri.parse(item.uri), null, null)
            } catch (e: SecurityException) {
                val rse = e as? RecoverableSecurityException
                    ?: return ActionResult.Failed("Permission denied for ${item.name}")
                val remaining = items.drop(index)
                return ActionResult.NeedsConsent(rse.userAction.actionIntent.intentSender) {
                    withContext(Dispatchers.IO) { deleteLegacy(remaining) }
                }
            }
        }
        return ActionResult.Done(deleted)
    }

    /**
     * Renames one item. On Android 11+ this first requests write consent, then updates
     * DISPLAY_NAME (MediaStore renames the file on disk).
     */
    fun rename(item: MediaItem, newName: String): ActionResult {
        val finalName = FileNames.withExtension(newName, item.name)
        when (val v = FileNames.validate(finalName)) {
            is FileNames.Validation.Invalid -> return ActionResult.Failed(v.reason)
            FileNames.Validation.Ok -> Unit
        }
        val uri = Uri.parse(item.uri)
        val apply: suspend () -> ActionResult = {
            withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, finalName) }
                    if (resolver.update(uri, values, null, null) > 0) ActionResult.Done(1)
                    else ActionResult.Failed("Couldn't rename ${item.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "rename failed", e)
                    ActionResult.Failed(friendly(e, "rename"))
                }
            }
        }
        return writeConsentThen(listOf(item), apply)
    }

    /** Moves items to another folder (relative path such as "Pictures/Holiday/"). */
    fun move(items: List<MediaItem>, targetRelativePath: String): ActionResult {
        val target = FileNames.normaliseRelativePath(targetRelativePath)
        if (target.isEmpty()) return ActionResult.Failed("Choose a folder")
        val apply: suspend () -> ActionResult = {
            withContext(Dispatchers.IO) {
                var moved = 0
                var failure: String? = null
                for (item in items) {
                    if (FileNames.normaliseRelativePath(item.relativePath) == target) continue
                    try {
                        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target) }
                        moved += resolver.update(Uri.parse(item.uri), values, null, null)
                    } catch (e: Exception) {
                        Log.w(TAG, "move failed for ${item.name}", e)
                        failure = friendly(e, "move")
                    }
                }
                if (failure != null && moved == 0) ActionResult.Failed(failure) else ActionResult.Done(moved)
            }
        }
        return writeConsentThen(items, apply)
    }

    private fun writeConsentThen(items: List<MediaItem>, apply: suspend () -> ActionResult): ActionResult =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActionResult.NeedsConsent(MediaStore.createWriteRequest(resolver, items.uris()).intentSender, apply)
        } else {
            ActionResult.RunNow(apply)
        }

    /**
     * Copies items into [targetRelativePath]. New files are owned by ProGallery, so no
     * consent dialog is needed. Each copy is written as "pending" and only published
     * once complete, so an interrupted copy never leaves a broken file visible.
     */
    suspend fun copy(items: List<MediaItem>, targetRelativePath: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): ActionResult =
        withContext(Dispatchers.IO) {
            val target = FileNames.normaliseRelativePath(targetRelativePath)
            if (target.isEmpty()) return@withContext ActionResult.Failed("Choose a folder")
            val existing = namesIn(target).toMutableSet()
            var copied = 0
            for ((i, item) in items.withIndex()) {
                val name = FileNames.uniqueName(item.name, existing)
                val ok = copyOne(item, target, name)
                if (ok) { copied++; existing += name }
                onProgress(i + 1, items.size)
            }
            if (copied == 0 && items.isNotEmpty()) ActionResult.Failed("Copy failed") else ActionResult.Done(copied)
        }

    private fun copyOne(item: MediaItem, target: String, name: String): Boolean {
        val collection = if (item.isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (item.dateTaken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, item.dateTaken)
        }
        val dest = try { resolver.insert(collection, values) } catch (e: Exception) {
            Log.w(TAG, "insert failed (is '$target' allowed for ${item.mimeType}?)", e); null
        } ?: return false
        return try {
            resolver.openInputStream(Uri.parse(item.uri)).use { input ->
                resolver.openOutputStream(dest, "w").use { output ->
                    requireNotNull(input) { "source unreadable" }
                    requireNotNull(output) { "destination unwritable" }
                    input.copyTo(output, 256 * 1024)
                }
            }
            resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "copy failed for ${item.name}", e)
            runCatching { resolver.delete(dest, null, null) }
            false
        }
    }

    private fun namesIn(relativePath: String): Set<String> {
        val out = HashSet<String>()
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        runCatching {
            resolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(out::add) }
        }
        return out
    }

    fun shareIntent(items: List<MediaItem>): Intent? {
        if (items.isEmpty()) return null
        val uris = ArrayList(items.map { Uri.parse(it.uri) })
        val mime = when {
            items.all { it.isImage } -> if (items.map { it.mimeType }.distinct().size == 1) items[0].mimeType else "image/*"
            items.all { it.isVideo } -> if (items.map { it.mimeType }.distinct().size == 1) items[0].mimeType else "video/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }

    fun setAsIntent(item: MediaItem): Intent =
        Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(Uri.parse(item.uri), item.mimeType)
            .putExtra("mimeType", item.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .let { Intent.createChooser(it, null) }

    fun openWithIntent(item: MediaItem): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), item.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .let { Intent.createChooser(it, null) }

    private fun List<MediaItem>.uris(): List<Uri> = map { Uri.parse(it.uri) }

    private fun friendly(e: Exception, verb: String): String = when (e) {
        is SecurityException -> "Android didn't allow ProGallery to $verb this file"
        is IllegalStateException, is IllegalArgumentException ->
            "Can't $verb here: ${e.message ?: "invalid destination"}"
        else -> "Couldn't $verb: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val TAG = "MediaActions"

        /** Top-level folders MediaStore accepts for images and videos. */
        val IMAGE_ROOTS = listOf("Pictures", "DCIM")
        val VIDEO_ROOTS = listOf("Movies", "DCIM", "Pictures")
    }
}

sealed interface ActionResult {
    data class Done(val count: Int) : ActionResult
    data class Failed(val message: String) : ActionResult

    /** Launch [intentSender]; when RESULT_OK, call [afterGrant] if present. */
    class NeedsConsent(val intentSender: IntentSender, val afterGrant: (suspend () -> ActionResult)? = null) : ActionResult

    /** No consent needed (Android 10 rename/move); just run it. */
    class RunNow(val run: suspend () -> ActionResult) : ActionResult
}
