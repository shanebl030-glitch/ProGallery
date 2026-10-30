package com.shaneblackburn.progallery.spike

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.VideoProject
import com.shaneblackburn.progallery.ui.theme.ProGalleryTheme
import com.shaneblackburn.progallery.video.ExoPlayerPreviewEngine

/**
 * DEBUG-ONLY spike screen: pick a video, which is split into two clips from the SAME source with a
 * transition between them, then played by the prototype [ExoPlayerPreviewEngine] (ExoPlayer playlist +
 * PREFER_SOFTWARE + per-clip setVideoEffects). Lets the per-clip transition be watched during
 * continuous playback on a real phone, with the transition type and software/hardware decode switchable
 * and the actually-selected decoder shown. Does not touch the production editor, export, or main screen.
 */
class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProGalleryTheme { Surface(Modifier.fillMaxSize()) { SpikeScreen() } } }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun SpikeScreen() {
    val context = LocalContext.current
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var meta by remember { mutableStateOf<VideoMeta?>(null) }
    var transition by remember { mutableStateOf(TransitionType.FADE_BLACK) }
    var preferSoftware by remember { mutableStateOf(true) }
    var decoderName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Pick a video to begin.") }
    var menuOpen by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf<ExoPlayerPreviewEngine?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            pickedUri = uri
            meta = readMeta(context, uri)
            status = meta?.let { "Loaded ${it.width}x${it.height}, ${it.durationMs} ms" } ?: "Couldn't read video metadata."
        }
    }

    // (Re)build the engine and load the two-clip-same-source project whenever the inputs change. The
    // decoder is selected at player-build time, so a software/hardware toggle rebuilds the engine.
    DisposableEffect(pickedUri, meta, transition, preferSoftware) {
        val uri = pickedUri
        val m = meta
        var eng: ExoPlayerPreviewEngine? = null
        if (uri != null && m != null && m.durationMs > 1000) {
            eng = ExoPlayerPreviewEngine(
                context, preferSoftware = preferSoftware,
                onDecoderName = { decoderName = it },
                onPlayerError = { status = "Player error: ${it.errorCodeName}" },
            )
            eng.load(twoClipProject(uri, m, transition), 0L, withEffects = true)
            engine = eng
            status = "Playing 2 same-source clips with ${transition.label} (${if (preferSoftware) "software" else "hardware"} preferred)."
        }
        onDispose { eng?.release() }
    }

    Column(
        Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("ExoPlayer preview-engine spike", style = MaterialTheme.typography.titleMedium)
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Video decoder: ${decoderName ?: "—"}", style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("video/*")) }) { Text("Pick video") }
            Button(onClick = { menuOpen = true }) { Text(transition.label) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                TransitionType.selectable.forEach { t ->
                    DropdownMenuItem(text = { Text(t.label) }, onClick = { transition = t; menuOpen = false })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = preferSoftware, onCheckedChange = { preferSoftware = it })
            Text(if (preferSoftware) "Prefer software decode" else "Prefer hardware decode")
        }

        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).also { engine?.attach(it) } },
                update = { engine?.attach(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { engine?.play() }) { Text("Play") }
            Button(onClick = { engine?.pause() }) { Text("Pause") }
            Button(onClick = { engine?.seekToTimeline(0L) }) { Text("Restart") }
        }
        Text(
            "Watch the join between the two halves — the ${transition.label} should play during continuous " +
                "playback. Prototype only; the production editor is unchanged.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private data class VideoMeta(val durationMs: Long, val width: Int, val height: Int, val rotation: Int)

private fun readMeta(context: Context, uri: Uri): VideoMeta? = runCatching {
    val mmr = MediaMetadataRetriever()
    mmr.setDataSource(context, uri)
    val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
    val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
    val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
    mmr.release()
    VideoMeta(dur, w, h, rot)
}.getOrNull()

private fun twoClipProject(uri: Uri, m: VideoMeta, type: TransitionType): VideoProject {
    val clip = Clip(id = 1, uri = uri.toString(), name = "spike", sourceDurationMs = m.durationMs, width = m.width, height = m.height)
    val base = VideoProject(listOf(clip))
    val mid = (base.durationMs / 2).coerceAtLeast(500)
    return base.splitAt(mid).setTransition(0, Transition(type, minOf(1000L, mid)))
}
