package com.shaneblackburn.progallery.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shaneblackburn.progallery.core.edit.EditHistory
import com.shaneblackburn.progallery.core.edit.EditState
import com.shaneblackburn.progallery.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

sealed interface EditorLoad {
    data object Loading : EditorLoad
    data class Ready(val width: Int, val height: Int) : EditorLoad
    data class Failed(val message: String) : EditorLoad
}

sealed interface SaveState {
    data object Idle : SaveState
    data class Saving(val progress: Float) : SaveState
    data class Saved(val uri: Uri?, val replaced: Boolean) : SaveState
    data class Failed(val message: String) : SaveState
}

/**
 * Holds the edit session for one photo: the decoded preview source, the edit history
 * (undo/redo), live preview rendering, and saving.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val saver = EditSaver(application)

    private var item: MediaItem? = null
    /** Upright, preview-sized original (≈ screen resolution). */
    private var previewSource: Bitmap? = null

    private val _load = MutableStateFlow<EditorLoad>(EditorLoad.Loading)
    val load: StateFlow<EditorLoad> = _load.asStateFlow()

    private val _history = MutableStateFlow(EditHistory())
    val history: StateFlow<EditHistory> = _history.asStateFlow()
    val state: EditState get() = _history.value.current

    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview.asStateFlow()

    /** Original (unedited) preview for before/after compare and the crop tool. */
    private val _original = MutableStateFlow<ImageBitmap?>(null)
    val original: StateFlow<ImageBitmap?> = _original.asStateFlow()

    private val _rendering = MutableStateFlow(false)
    val rendering: StateFlow<Boolean> = _rendering.asStateFlow()

    private val _save = MutableStateFlow<SaveState>(SaveState.Idle)
    val save: StateFlow<SaveState> = _save.asStateFlow()

    /** When true, overlays are drawn by the UI (for live manipulation), not baked into the preview. */
    var overlaysLive = false
        set(value) { field = value; requestRender() }

    /** Uncropped, geometry-applied preview shown by the crop tool. */
    private val _cropBase = MutableStateFlow<ImageBitmap?>(null)
    val cropBase: StateFlow<ImageBitmap?> = _cropBase.asStateFlow()

    /** While true, the crop tool is open and needs [cropBase] instead of the full render. */
    var cropMode = false
        set(value) { field = value; requestRender(immediate = true) }

    private var renderJob: Job? = null
    private var saveJob: Job? = null

    fun open(media: MediaItem, previewMaxSide: Int) {
        if (item?.id == media.id && previewSource != null) return
        item = media
        _load.value = EditorLoad.Loading
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    EditRenderer.decode(getApplication<Application>().contentResolver, Uri.parse(media.uri), previewMaxSide)
                }
                previewSource = bmp
                _original.value = bmp.asImageBitmap()
                _load.value = EditorLoad.Ready(media.displayWidth, media.displayHeight)
                requestRender(immediate = true)
            } catch (e: Exception) {
                Log.e(TAG, "decode failed", e)
                _load.value = EditorLoad.Failed("Couldn't open this photo for editing (${e.message ?: e.javaClass.simpleName}).")
            } catch (e: OutOfMemoryError) {
                _load.value = EditorLoad.Failed("This photo is too large to edit on this device.")
            }
        }
    }

    // ---- editing ----

    /** Live change (e.g. while dragging a slider): updates preview, merges into one undo step. */
    fun preview(change: (EditState) -> EditState) {
        _history.update { it.replaceCurrent(change(it.current)) }
        requestRender()
    }

    /** Marks the start of a gesture so the whole drag becomes a single undo step. */
    fun beginChange() = _history.update { it.checkpoint() }

    /** Discrete change (button tap): one undo step. */
    fun commit(change: (EditState) -> EditState) {
        _history.update { it.push(change(it.current)) }
        requestRender()
    }

    fun undo() { _history.update { it.undo() }; requestRender(immediate = true) }
    fun redo() { _history.update { it.redo() }; requestRender(immediate = true) }
    fun resetAll() = commit { EditState() }

    val hasChanges: Boolean get() = !state.isIdentity

    private fun requestRender(immediate: Boolean = false) {
        val src = previewSource ?: return
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            if (!immediate) delay(16) // coalesce rapid slider events into ~60 fps
            _rendering.value = true
            try {
                if (cropMode) {
                    // Crop tool: show the whole oriented/straightened image; the UI draws the crop box.
                    val g = state.geometry.copy(crop = com.shaneblackburn.progallery.core.edit.NormRect.FULL)
                    val base = EditRenderer.applyGeometry(src, g)
                    ensureActive()
                    _cropBase.value = base.asImageBitmap()
                    return@launch
                }
                val s = state.let { if (overlaysLive) it.copy(overlays = emptyList(), frame = null) else it }
                val ctx = coroutineContext
                val out = EditRenderer.render(src, s, isCancelled = { !ctx.isActiveSafe() })
                ctx.ensureActive()
                _preview.value = out.asImageBitmap()
            } catch (e: java.util.concurrent.CancellationException) {
                // superseded by a newer render
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "preview OOM", e)
            } finally {
                _rendering.value = false
            }
        }
    }

    private fun kotlin.coroutines.CoroutineContext.isActiveSafe(): Boolean =
        this[Job]?.isActive != false

    // ---- saving ----

    fun saveCopy(options: ExportOptions) = runSave(replace = false, options)

    /** Caller must have obtained write consent first on Android 11+. */
    fun replaceOriginal(options: ExportOptions) = runSave(replace = true, options)

    fun cancelSave() {
        saveJob?.cancel()
        _save.value = SaveState.Idle
    }

    fun consumeSaveResult() { _save.value = SaveState.Idle }

    fun canReplace(): Boolean = item?.let { saver.canReplace(it) } ?: false

    private fun runSave(replace: Boolean, options: ExportOptions) {
        val media = item ?: return
        val editState = state
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.Default) {
            _save.value = SaveState.Saving(0f)
            var full: Bitmap? = null
            var rendered: Bitmap? = null
            try {
                val resolver = getApplication<Application>().contentResolver
                full = withContext(Dispatchers.IO) { EditRenderer.decode(resolver, Uri.parse(media.uri), options.maxSide) }
                _save.value = SaveState.Saving(0.15f)
                val ctx = coroutineContext
                rendered = EditRenderer.render(full, editState, isCancelled = { !ctx.isActiveSafe() }) { p ->
                    _save.value = SaveState.Saving(0.15f + p * 0.7f)
                }
                full.recycle(); full = null
                ensureActive()
                _save.value = SaveState.Saving(0.9f)
                val uri = if (replace) { saver.replaceOriginal(media, rendered, options); null }
                else saver.saveCopy(media, rendered, options)
                _save.value = SaveState.Saved(uri, replace)
            } catch (e: java.util.concurrent.CancellationException) {
                // Either the coroutine was cancelled or the renderer stopped early.
                _save.value = SaveState.Idle
                if (!isActive) throw e
            } catch (e: OutOfMemoryError) {
                _save.value = SaveState.Failed("Not enough memory to save at full size. Try a smaller export size.")
            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
                _save.value = SaveState.Failed(e.message ?: "Saving failed")
            } finally {
                full?.recycle()
                rendered?.recycle()
            }
        }
    }

    override fun onCleared() {
        renderJob?.cancel()
        saveJob?.cancel()
        // Preview bitmaps are referenced by ImageBitmaps in the UI; let GC reclaim them.
        previewSource = null
    }

    private companion object { const val TAG = "EditorViewModel" }
}
