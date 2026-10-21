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
import com.shaneblackburn.progallery.core.video.ProjectDoc
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

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

@OptIn(UnstableApi::class, kotlinx.coroutines.FlowPreview::class)
class VideoEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ProGalleryApp
    val export: VideoExportManager get() = app.videoExport
    private val store: ProjectStore get() = app.projects

    private val _history = MutableStateFlow(ProjectHistory())
    val history: StateFlow<ProjectHistory> = _history.asStateFlow()
    val project: VideoProject get() = _history.value.current

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Clip/music URIs that can no longer be opened (deleted, moved, or access removed). */
    private val _missing = MutableStateFlow<Set<String>>(emptySet())
    val missing: StateFlow<Set<String>> = _missing.asStateFlow()

    /** Thumbnails per clip uri: list of frames across the whole source. */
    private val thumbCache = ConcurrentHashMap<String, List<ImageBitmap>>()
    private val _thumbs = MutableStateFlow<Map<String, List<ImageBitmap>>>(emptyMap())
    val thumbs: StateFlow<Map<String, List<ImageBitmap>>> = _thumbs.asStateFlow()
    private val thumbLimiter = Semaphore(2)

    /** The saved-project record this session edits (id, name, export folder). */
    private val _doc = MutableStateFlow<ProjectDoc?>(null)
    val doc: StateFlow<ProjectDoc?> = _doc.asStateFlow()

    /**
     * True when the project existed in the Projects list before this session, or the user
     * created it explicitly (New video). A project started with "Edit" on a single video is
     * only kept once the user asks to keep it.
     */
    private val _persisted = MutableStateFlow(false)
    val persisted: StateFlow<Boolean> = _persisted.asStateFlow()

    val sourceName: String get() = _doc.value?.name ?: "Video"
    val sourceFolder: String get() = _doc.value?.exportFolder ?: "Movies/ProGallery/"

    private var started = false

    init {
        // Autosave: shortly after each change, write the project if it's being kept.
        viewModelScope.launch {
            _history.map { it.current }.distinctUntilChanged().debounce(700).collect { saveNow() }
        }
    }

    /** "Edit" on a single video from the viewer. */
    fun open(item: MediaItem) {
        if (started) return
        started = true
        viewModelScope.launch {
            val clip = probe(item)
            if (clip == null) {
                _error.value = "Couldn't read this video."
            } else {
                _doc.value = store.newDoc(VideoProject(listOf(clip)), item.name.substringBeforeLast('.'),
                    item.relativePath.ifBlank { "Movies/ProGallery/" })
                _history.value = ProjectHistory(current = VideoProject(listOf(clip)))
                loadThumbs(clip)
            }
            _loading.value = false
        }
    }

    /** "New video" from picked photos and videos; the project is kept straight away. */
    fun openNew(items: List<MediaItem>) {
        if (started) return
        started = true
        viewModelScope.launch {
            val clips = items.mapNotNull { probe(it) }
            if (clips.isEmpty()) {
                _error.value = "Couldn't read the selected items."
            } else {
                val project = VideoProject(clips)
                val name = "Video " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date())
                _doc.value = store.newDoc(project, name, "Movies/ProGallery/")
                _persisted.value = true
                _history.value = ProjectHistory(current = project)
                saveNow()
                clips.forEach { loadThumbs(it) }
                if (clips.size < items.size) _error.value = "${items.size - clips.size} item(s) couldn't be read and were skipped."
            }
            _loading.value = false
        }
    }

    /** Resume a saved project. */
    fun openProject(id: String) {
        if (started) return
        started = true
        viewModelScope.launch {
            val d = store.load(id)
            if (d == null) {
                _error.value = "This project couldn't be opened."
            } else {
                _doc.value = d
                _persisted.value = true
                _history.value = ProjectHistory(current = d.project)
                checkMissing(d.project)
                d.project.clips.distinctBy { it.uri }.filter { it.uri !in _missing.value }.forEach { loadThumbs(it) }
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

    /** Appends photos/videos picked in the in-app picker. */
    fun addMedia(items: List<MediaItem>) {
        viewModelScope.launch {
            val clips = items.mapNotNull { probe(it) }
            if (clips.isNotEmpty()) commit { it.copy(clips = it.clips + clips) }
            clips.forEach { loadThumbs(it) }
            if (clips.size < items.size) _error.value = "${items.size - clips.size} item(s) couldn't be added."
        }
    }

    /** Removes every clip (and music) whose source can't be opened any more. */
    fun removeMissing() {
        val gone = _missing.value
        commit { p ->
            var q = p
            for (i in p.clips.indices.reversed()) if (p.clips[i].uri in gone) q = q.removeClip(i)
            if (q.music?.uri in gone) q = q.copy(music = null)
            q
        }
        _missing.value = emptySet()
    }

    fun setMusic(uri: Uri) {
        viewModelScope.launch {
            // Keep access across restarts so a saved project can still play its music.
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val dur = withContext(Dispatchers.IO) { durationOf(uri) }
            if (dur <= 0) { _error.value = "Couldn't read that audio file."; return@launch }
            val name = displayName(uri) ?: "Music"
            commit { it.copy(music = MusicTrack(uri.toString(), name, dur)) }
        }
    }

    fun rename(name: String) {
        _doc.update { it?.copy(name = name.trim().ifEmpty { it.name }) }
        viewModelScope.launch { saveNow() }
    }

    /** Keep a project that was started from a single video (adds it to Video projects). */
    fun keepProject() {
        _persisted.value = true
        viewModelScope.launch { saveNow() }
    }

    fun clearError() { _error.value = null }

    fun startExport(settings: VideoExportSettings) {
        export.start(project, settings, sourceName, sourceFolder)
    }

    private suspend fun saveNow() {
        val d = _doc.value ?: return
        if (!_persisted.value || project.isEmpty) return
        runCatching { _doc.value = store.save(d.copy(project = project)) }
            .onFailure { Log.e(TAG, "autosave failed", it); _error.value = "Couldn't save the project (${it.message})." }
    }

    override fun onCleared() {
        // Final save of any change made in the last debounce window.
        if (_persisted.value) kotlinx.coroutines.runBlocking { saveNow() }
    }

    private suspend fun checkMissing(p: VideoProject) {
        val uris = (p.clips.map { it.uri } + listOfNotNull(p.music?.uri)).distinct()
        _missing.value = withContext(Dispatchers.IO) {
            uris.filterNot { u ->
                runCatching { getApplication<Application>().contentResolver.openFileDescriptor(Uri.parse(u), "r")?.use { true } ?: false }
                    .getOrDefault(false)
            }.toSet()
        }
    }

    // ---- media probing ----

    /** Builds a clip for a gallery item (photo or video); null if it can't be read. */
    private suspend fun probe(item: MediaItem): Clip? =
        if (item.isImage) probeImage(item) else probeClip(Uri.parse(item.uri), item.name)

    private suspend fun probeImage(item: MediaItem): Clip? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(item.uri)
        val resolver = getApplication<Application>().contentResolver
        // RAW pixel dimensions (before EXIF rotation): Media3 decodes images without applying
        // EXIF, so the composer rotates them upright using [sourceRotation].
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } }
        var rawW = opts.outWidth
        var rawH = opts.outHeight
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rawW <= 0 || rawH <= 0) {
            // Fall back to MediaStore's (already-oriented) size, undoing the rotation to raw.
            if (item.displayWidth <= 0) return@withContext null
            rawW = if (rotation % 180 == 90) item.displayHeight else item.displayWidth
            rawH = if (rotation % 180 == 90) item.displayWidth else item.displayHeight
        }
        Clip(
            id = System.nanoTime(), uri = item.uri, name = item.name, sourceDurationMs = Clip.DEFAULT_IMAGE_MS,
            width = rawW, height = rawH, sourceRotation = ((rotation % 360) + 360) % 360,
            hasAudio = false, isImage = true, mimeType = item.mimeType.ifBlank { null },
        ).withImageDuration(Clip.DEFAULT_IMAGE_MS)
    }

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
                frameRate = meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.roundToInt()
                    ?.takeIf { it in 1..480 } ?: 30,
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
        if (clip.isImage) {
            thumbLimiter.withPermit {
                val bmp = runCatching {
                    getApplication<Application>().contentResolver.loadThumbnail(Uri.parse(clip.uri), android.util.Size(240, 240), null)
                }.getOrNull()
                if (bmp != null) { thumbCache[clip.uri] = listOf(bmp.asImageBitmap()); _thumbs.value = HashMap(thumbCache) }
            }
            return@launch
        }
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
