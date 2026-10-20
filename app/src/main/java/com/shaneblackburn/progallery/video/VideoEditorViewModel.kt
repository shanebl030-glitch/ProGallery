package com.shaneblackburn.progallery.video

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.shaneblackburn.progallery.ProGalleryApp
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.MusicTrack
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Undo/redo for video projects (same rules as the photo editor's history). */
data class ProjectHistory(
    val past: List<VideoProject> = emptyList(),
    val current: VideoProject = VideoProject(),
    val future: List<VideoProject> = emptyList(),
) {
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun push(p: VideoProject) = if (p == current) this else copy(past = (past + current).takeLast(60), current = p, future = emptyList())
    fun checkpoint() = if (past.lastOrNull() == current) this else copy(past = (past + current).takeLast(60), future = emptyList())
    fun replace(p: VideoProject) = if (p == current) this else copy(current = p, future = emptyList())
    fun undo(): ProjectHistory {
        var p = past
        while (p.isNotEmpty() && p.last() == current) p = p.dropLast(1)
        if (p.isEmpty()) return copy(past = p)
        return copy(past = p.dropLast(1), current = p.last(), future = listOf(current) + future)
    }
    fun redo() = if (future.isEmpty()) this else copy(past = past + current, current = future.first(), future = future.drop(1))
}

@OptIn(UnstableApi::class)
class VideoEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ProGalleryApp
    val export: VideoExportManager get() = app.videoExport

    private val _history = MutableStateFlow(ProjectHistory())
    val history: StateFlow<ProjectHistory> = _history.asStateFlow()
    val project: VideoProject get() = _history.value.current

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Thumbnails per clip uri: list of frames across the whole source. */
    private val thumbCache = ConcurrentHashMap<String, List<ImageBitmap>>()
    private val _thumbs = MutableStateFlow<Map<String, List<ImageBitmap>>>(emptyMap())
    val thumbs: StateFlow<Map<String, List<ImageBitmap>>> = _thumbs.asStateFlow()
    private val thumbLimiter = Semaphore(2)

    var sourceName: String = "Video"
        private set
    var sourceFolder: String = "Movies/ProGallery/"
        private set

    private var started = false

    fun open(item: MediaItem) {
        if (started) return
        started = true
        sourceName = item.name.substringBeforeLast('.')
        sourceFolder = item.relativePath.ifBlank { "Movies/ProGallery/" }
        viewModelScope.launch {
            val clip = probeClip(Uri.parse(item.uri), item.name)
            if (clip == null) {
                _error.value = "Couldn't read this video."
            } else {
                _history.value = ProjectHistory(current = VideoProject(listOf(clip)))
                loadThumbs(clip)
            }
            _loading.value = false
        }
    }

    // ---- editing ----
    fun commit(change: (VideoProject) -> VideoProject) = _history.update { it.push(change(it.current)) }
    fun previewChange(change: (VideoProject) -> VideoProject) = _history.update { it.replace(change(it.current)) }
    fun beginChange() = _history.update { it.checkpoint() }
    fun undo() = _history.update { it.undo() }
    fun redo() = _history.update { it.redo() }

    fun addClip(uri: Uri) {
        viewModelScope.launch {
            val name = displayName(uri) ?: "Clip"
            val clip = probeClip(uri, name)
            if (clip == null) { _error.value = "Couldn't add that video."; return@launch }
            commit { it.copy(clips = it.clips + clip) }
            loadThumbs(clip)
        }
    }

    fun setMusic(uri: Uri) {
        viewModelScope.launch {
            val dur = withContext(Dispatchers.IO) { durationOf(uri) }
            if (dur <= 0) { _error.value = "Couldn't read that audio file."; return@launch }
            val name = displayName(uri) ?: "Music"
            commit { it.copy(music = MusicTrack(uri.toString(), name, dur)) }
        }
    }

    fun clearError() { _error.value = null }

    fun startExport(settings: VideoExportSettings) {
        export.start(project, settings, sourceName, sourceFolder)
    }

    // ---- media probing ----

    private suspend fun probeClip(uri: Uri, name: String): Clip? = withContext(Dispatchers.IO) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(getApplication(), uri)
            fun meta(k: Int) = mmr.extractMetadata(k)
            val dur = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext null
            if (meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return@withContext null
            Clip(
                id = System.nanoTime(),
                uri = uri.toString(),
                name = name,
                sourceDurationMs = dur,
                width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920,
                height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080,
                sourceRotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } catch (e: Exception) {
            Log.w(TAG, "probe failed for $uri", e)
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun durationOf(uri: Uri): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(getApplication(), uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { runCatching { mmr.release() } }
    }

    private suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    /** Extracts ~1 frame per 1.5 s (max 40) of the whole source for the timeline strip. */
    private fun loadThumbs(clip: Clip): Job = viewModelScope.launch(Dispatchers.IO) {
        if (thumbCache.containsKey(clip.uri)) return@launch
        thumbLimiter.withPermit {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(getApplication(), Uri.parse(clip.uri))
                val count = (clip.sourceDurationMs / 1500L).toInt().coerceIn(4, 40)
                val frames = ArrayList<ImageBitmap>(count)
                for (i in 0 until count) {
                    val tUs = clip.sourceDurationMs * 1000L * i / count
                    val bmp: Bitmap? = runCatching {
                        mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                    }.getOrNull()
                    if (bmp != null) frames += bmp.asImageBitmap()
                }
                thumbCache[clip.uri] = frames
                _thumbs.value = HashMap(thumbCache)
            } catch (e: Exception) {
                Log.w(TAG, "thumbs failed", e)
            } finally {
                runCatching { mmr.release() }
            }
        }
    }

    private companion object { const val TAG = "VideoEditorVM" }
}
