package com.shaneblackburn.progallery.video

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.shaneblackburn.progallery.core.util.FileNames
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progress: Float, val title: String) : ExportState
    data class Done(val uri: Uri, val title: String) : ExportState
    data class Failed(val message: String) : ExportState
    data object Cancelled : ExportState
}

/**
 * App-wide video export controller. Transformer must run on a Looper thread, so all
 * calls happen on the main thread; heavy work happens inside Transformer's own threads.
 * A foreground service ([VideoExportService]) keeps the process alive while exporting.
 */
@OptIn(UnstableApi::class)
class VideoExportManager(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var transformer: Transformer? = null
    private var tempFile: File? = null
    private var title = ""
    private val progressHolder = ProgressHolder()

    val isRunning: Boolean get() = _state.value is ExportState.Running

    /**
     * Starts exporting [project]. [baseName] is used for the output file name and
     * [relativePath] is the destination folder (e.g. "Movies/ProGallery/").
     */
    fun start(project: VideoProject, settings: VideoExportSettings, baseName: String, relativePath: String) {
        main.post {
            if (isRunning) return@post
            val composition = try {
                ProjectComposer.build(project, settings)
            } catch (e: Exception) {
                _state.value = ExportState.Failed(e.message ?: "Couldn't prepare the export"); return@post
            }
            val (w, h) = ProjectComposer.outputSize(project, settings)
            val bitrate = ProjectComposer.bitrate(w, h, settings.frameRate, settings.quality, settings.codec)
            val out = File(context.cacheDir, "export-${System.currentTimeMillis()}.mp4")
            tempFile = out
            title = baseName
            val encoder = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .setEnableFallback(true) // falls back to a supported size/bitrate/codec if needed
                .build()
            val t = Transformer.Builder(context)
                .setVideoMimeType(settings.codec.mime)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        transformer = null
                        publish(out, baseName, relativePath)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        Log.e(TAG, "export failed", exportException)
                        transformer = null
                        out.delete()
                        _state.value = ExportState.Failed(friendly(exportException))
                    }
                })
                .build()
            transformer = t
            _state.value = ExportState.Running(0f, baseName)
            // Keep the process alive + show progress while exporting. If the OS refuses the
            // foreground service (background-start restrictions on Android 14+/16), the export
            // still runs in-process and shows progress in the app — so a refusal must not crash.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VideoExportService::class.java)) }
                .onFailure { Log.w(TAG, "couldn't start the export service; exporting in-process", it) }
            try {
                t.start(composition, out.absolutePath)
                pollProgress()
            } catch (e: Exception) {
                transformer = null
                out.delete()
                _state.value = ExportState.Failed(e.message ?: "Export couldn't start")
            }
        }
    }

    fun cancel() {
        main.post {
            transformer?.cancel()
            transformer = null
            tempFile?.delete()
            if (isRunning) _state.value = ExportState.Cancelled
        }
    }

    /** Clears a finished/failed/cancelled state once the UI has shown it. */
    fun acknowledge() {
        if (!isRunning) _state.value = ExportState.Idle
    }

    private fun pollProgress() {
        val t = transformer ?: return
        if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            _state.value = ExportState.Running(progressHolder.progress / 100f * 0.97f, title)
        }
        main.postDelayed({ pollProgress() }, 250)
    }

    /** Copies the finished temp file into MediaStore (pending until complete). */
    private fun publish(file: File, baseName: String, relativePath: String) {
        scope.launch {
            val resolver = context.contentResolver
            var uri: Uri? = null
            try {
                val folder = FileNames.normaliseRelativePath(relativePath).ifEmpty { "Movies/ProGallery/" }
                val name = FileNames.editedCopyName("$baseName.mp4", existingNames(folder), "mp4")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                uri = runCatching { resolver.insert(collection, values) }.getOrNull()
                    ?: resolver.insert(collection, values.apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ProGallery/") })
                    ?: error("Couldn't create the video file")
                resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out, 512 * 1024) } }
                    ?: error("Couldn't write the video file")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                _state.value = ExportState.Done(uri, name)
            } catch (e: Exception) {
                Log.e(TAG, "publish failed", e)
                uri?.let { runCatching { resolver.delete(it, null, null) } }
                _state.value = ExportState.Failed("Couldn't save the video: ${e.message}")
            } finally {
                file.delete()
            }
        }
    }

    private fun existingNames(relativePath: String): Set<String> {
        val out = HashSet<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath), null,
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let(out::add) }
        }
        return out
    }

    private fun friendly(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
            "This device can't encode that format or size. Try H.264 or a lower resolution."
        ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "One of the clips uses a format this device can't decode."
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> "A clip or music file is no longer available."
        else -> "Export failed (${e.errorCodeName})."
    }

    private companion object { const val TAG = "VideoExport" }
}
