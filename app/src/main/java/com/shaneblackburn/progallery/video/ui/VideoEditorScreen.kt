package com.shaneblackburn.progallery.video.ui

import android.Manifest
import android.os.Build
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.shaneblackburn.progallery.R
import com.shaneblackburn.progallery.core.format.Formatters
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.video.TimedText
import com.shaneblackburn.progallery.core.video.TimelineMath
import com.shaneblackburn.progallery.ui.theme.ProGalleryTheme
import com.shaneblackburn.progallery.ui.theme.ThemeMode
import com.shaneblackburn.progallery.video.ExportState
import com.shaneblackburn.progallery.video.FramePreviewRenderer
import com.shaneblackburn.progallery.video.PreviewController
import com.shaneblackburn.progallery.video.VideoEditorViewModel
import com.shaneblackburn.progallery.video.VideoExportSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlin.math.roundToInt

/** How the video editor was opened. */
sealed interface VideoOpen {
    /** "Edit" on one video in the viewer. */
    data class Item(val item: MediaItem) : VideoOpen
    /** "New video" from picked gallery items. */
    data class New(val items: List<MediaItem>) : VideoOpen
    /** Resume a saved project. */
    data class Project(val id: String) : VideoOpen
}

/**
 * @param pickerItems gallery items offered by "Add media" (the timeline, newest first).
 */
@Composable
fun VideoEditorScreen(
    open: VideoOpen,
    pickerItems: List<MediaItem>,
    onClose: () -> Unit,
    onCreated: (projectId: String) -> Unit = {},
) {
    ProGalleryTheme(mode = ThemeMode.DARK, dynamicColor = false) {
        Surface(color = Color(0xFF0E0E10), contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
            VideoEditorContent(open, pickerItems, onClose, onCreated)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoEditorContent(open: VideoOpen, pickerItems: List<MediaItem>, onClose: () -> Unit, onCreated: (String) -> Unit) {
    val vm: VideoEditorViewModel = viewModel()
    val context = LocalContext.current
    val history by vm.history.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val thumbs by vm.thumbs.collectAsState()
    val exportState by vm.export.state.collectAsState()
    val doc by vm.doc.collectAsState()
    val persisted by vm.persisted.collectAsState()
    val missing by vm.missing.collectAsState()
    val project = history.current
    var showPicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    var selection by remember { mutableStateOf<TimelineSelection?>(null) }
    var pxPerMs by remember { mutableFloatStateOf(0.06f) } // 60 px per second
    var positionMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    // True only while the user is dragging the timeline (not during programmatic scrolls).
    var scrubbing by remember { mutableStateOf(false) }
    // Set while the app scrolls the timeline itself (playback follow / explicit seek) so those
    // scrolls are not mistaken for a user scrub — which would quantise the frame-accurate
    // position to a pixel boundary and fight the real source of truth.
    var programmaticScroll by remember { mutableStateOf(false) }
    // Latest-wins, throttled seek target for the heavy CompositionPlayer.seekTo (see below).
    val pendingSeekMs = remember { MutableStateFlow(-1L) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var simplifiedPreview by remember { mutableStateOf(false) }
    var fallbackReason by remember { mutableStateOf<String?>(null) }
    var showFallbackDetails by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var textEdit by remember { mutableStateOf<TimedText?>(null) }
    var settings by remember { mutableStateOf(VideoExportSettings()) }
    // Safe preview mode skips the GPU effects pipeline in the preview (effects still export).
    // Persisted; auto-enabled after a video-editor crash so the device isn't stuck crash-looping.
    // Auto-recovery: if Safe mode was auto-enabled (not chosen by the user) and the crash back-off isn't
    // spent, attempt the full-effects pipeline this session instead of forcing the raw player. It's
    // cleared only after a CONFIRMED successful full-effects session (first frame rendered + stable).
    val recoveryTrial = remember { com.shaneblackburn.progallery.CrashReporter.shouldRecoveryTrial() }
    var safeMode by remember {
        mutableStateOf(if (recoveryTrial) false else com.shaneblackburn.progallery.CrashReporter.isSafePreview())
    }
    var recoveryActive by remember { mutableStateOf(recoveryTrial) }
    var fullFirstFrame by remember { mutableStateOf(false) }
    // Opt-in sync diagnostic (long-press the timecode to toggle). Shows the playhead position, the
    // player's actual reported position and the frame they map to, so a sync mismatch can be read
    // off the screen instead of guessed at. Off by default; no cost unless shown.
    var showSyncDebug by remember { mutableStateOf(false) }
    var playerPosMs by remember { mutableLongStateOf(0L) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Scroll the timeline programmatically to [ms], flagging it so the scrub collector doesn't treat
    // the move as a user drag. CRITICAL: the flag is cleared in a finally, because scroll.scrollTo is
    // a suspend call and this runs inside coroutines that get CANCELLED when `playing` flips (pause /
    // playback end) mid-scroll — without finally the cancellation would skip clearing the flag,
    // leaving programmaticScroll stuck true, after which scrubbing never updates positionMs and the
    // preview freezes while the playhead/thumbnails move freely (the reported desync, in both modes).
    val scrollToMs: suspend (Long) -> Unit = { ms ->
        programmaticScroll = true
        try {
            scroll.scrollTo(TimelineMath.scrollPxForMs(ms, pxPerMs))
        } finally {
            programmaticScroll = false
        }
    }
    LaunchedEffect(Unit) { com.shaneblackburn.progallery.CrashReporter.setPhase("video:editor") }

    // A brand-new project has been written to storage: let the caller switch to its saved route.
    LaunchedEffect(persisted, doc?.id, loading) {
        val id = doc?.id
        if (open is VideoOpen.New && persisted && !loading && id != null) onCreated(id)
    }
    LaunchedEffect(open) {
        when (open) {
            is VideoOpen.Item -> vm.open(open.item)
            is VideoOpen.New -> vm.openNew(open.items)
            is VideoOpen.Project -> vm.openProject(open.id)
        }
    }
    LaunchedEffect(error) { error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.clearError() } }

    // ---- Preview player (same Composition as export; rebuilt on every change)
    val preview = remember {
        PreviewController(
            context,
            onPlayingChanged = { playing = it },
            onError = { previewError = it },
            onFirstFrame = { fullFirstFrame = true },
            onMode = { simplifiedPreview = it == com.shaneblackburn.progallery.video.PreviewMode.SIMPLIFIED },
            onFallbackReason = { fallbackReason = it },
            safeMode = { safeMode },
        )
    }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    // Safe-mode recovery: clear an AUTO-enabled Safe mode only after a CONFIRMED successful full-effects
    // session — CompositionPlayer stayed in full mode, rendered a frame (fullFirstFrame), and remained
    // stable for the window. If it falls back to simplified (simplifiedPreview flips) the delay is
    // cancelled and nothing clears; a crash re-arms Safe mode via the crash handler. Manual Safe mode is
    // never cleared here (clearSafePreviewAfterSuccess is a no-op unless Safe mode was auto-enabled).
    LaunchedEffect(recoveryActive, fullFirstFrame, simplifiedPreview) {
        if (recoveryActive && fullFirstFrame && !simplifiedPreview) {
            kotlinx.coroutines.delay(com.shaneblackburn.progallery.core.video.SafePreviewPolicy.STABILITY_MS)
            if (!simplifiedPreview) { // still full-effects after the stability window
                com.shaneblackburn.progallery.CrashReporter.clearSafePreviewAfterSuccess()
                recoveryActive = false
            }
        }
    }
    // On-demand composed preview: when the full-effects live player can't run the composition and falls
    // back to the simplified (raw) player, render the EXACT export frame — colour, geometry and the
    // transition — at the playhead via Media3 FrameExtractor (software decode) and show it while paused
    // or scrubbing. This is what makes a transition visible on devices whose hardware decoder can't run
    // a multi-clip composition. Playback and export are unchanged; the frame uses the same per-clip
    // effects as export.
    val frameRenderer = remember { FramePreviewRenderer(context) }
    DisposableEffect(Unit) { onDispose { frameRenderer.release() } }
    var composedFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    // Bumped to request a fresh composed frame (position moved, project edited, or mode/pause changed).
    val composedTick = remember { MutableStateFlow(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) preview.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // Rebuild the preview shortly after the project stops changing.
    LaunchedEffect(Unit) {
        vm.history.map { it.current }
            .distinctUntilChanged()
            .debounce(350)
            .collect { p ->
                // A missing source would make the whole preview fail; wait until it's resolved.
                if (p.clips.isNotEmpty() && p.clips.none { it.uri in vm.missing.value }) preview.show(p, positionMs)
            }
    }
    // Toggling Safe preview mode rebuilds the preview with/without the effects pipeline.
    LaunchedEffect(safeMode) {
        val p = vm.project
        if (p.clips.isNotEmpty() && p.clips.none { it.uri in vm.missing.value }) preview.show(p, positionMs)
    }
    // Foldables (e.g. Galaxy Z Fold5) switch between the cover and main displays, and phones
    // rotate: the window resizes without the Activity being recreated. Rebuild the preview so the
    // player gets a fresh surface for the new size instead of rendering onto a stale one.
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp) {
        val p = vm.project
        if (p.clips.isNotEmpty() && p.clips.none { it.uri in vm.missing.value }) preview.show(p, positionMs)
    }
    // The playhead position (positionMs) is the single source of truth for the preview frame,
    // the timecode and where the timeline is scrolled. It is written by exactly one driver at a
    // time: the player while playing, the user while scrubbing, or an explicit seek/frame-step.
    //
    // Preview seeks are throttled latest-wins (~15×/s) so a fast drag can't flood the player; the
    // player supersedes any in-flight seek, so this converges on the newest target. The playhead,
    // timecode and timeline all track the finger live (positionMs below); the final resting frame
    // is always seeked exactly on release (further below).
    LaunchedEffect(Unit) {
        pendingSeekMs.filter { it >= 0 }.sample(66).collect { preview.scrubTo(it) }
    }
    // While playing, the player's clock is the single source of truth: follow its position on
    // every animation frame (vsync-aligned, not a fixed 33 ms timer) so the playhead and timecode
    // stay locked to the preview the user actually sees. When playback stops (pause or end), snap
    // once to the player's exact final position — the follow loop can otherwise be up to one frame
    // behind at the instant isPlaying flips, which would leave the playhead and the frozen preview
    // frame slightly apart. This LaunchedEffect re-runs whenever `playing` changes.
    LaunchedEffect(playing) {
        if (playing) {
            // Stop following the instant a scrub starts, so a drag mid-playback isn't fought by the
            // follow-scroll (the scrub pauses the player a frame later via beginScrub).
            while (playing && !scrubbing) {
                withFrameMillis { } // wait for the next frame, then sample the player
                positionMs = preview.currentPositionMs.coerceIn(0, maxOf(0, project.durationMs))
                scrollToMs(positionMs)
            }
        } else if (!scrubbing) {
            // Stopped and the user isn't dragging: align exactly to where the player really is.
            positionMs = preview.currentPositionMs.coerceIn(0, maxOf(0, project.durationMs))
            scrollToMs(positionMs)
        }
    }
    // While the user drags the timeline, the scroll drives: move the playhead with the finger
    // immediately (zero lag) and throttle the actual preview seek. Dragging takes control even
    // during playback — beginScrub pauses the player and enters scrubbing mode, so playback stops at
    // the dragged position and does not resume on release. A programmatic scroll (the playback
    // follow above, or an explicit seek below) is ignored here so it can't be mistaken for a drag.
    LaunchedEffect(pxPerMs) {
        snapshotFlow { scroll.value to scroll.isScrollInProgress }.collect { (v, active) ->
            when {
                active && !programmaticScroll -> {
                    if (!scrubbing) { scrubbing = true; preview.beginScrub() }
                    positionMs = TimelineMath.msForScrollPx(v, pxPerMs, vm.project.durationMs)
                    pendingSeekMs.value = positionMs
                }
                !active && scrubbing -> {
                    scrubbing = false
                    pendingSeekMs.value = -1L      // cancel any pending throttled seek…
                    preview.endScrub(positionMs)   // …land exactly here, leave scrubbing mode, stay paused
                }
            }
        }
    }
    // Sample the player's real position for the opt-in sync diagnostic (see showSyncDebug).
    LaunchedEffect(showSyncDebug) {
        while (showSyncDebug) { playerPosMs = preview.currentPositionMs; delay(120) }
    }
    // ---- Composed-frame preview driver (see frameRenderer above) --------------------------------
    // Request a new composed frame whenever the playhead moves or the overlay state changes…
    LaunchedEffect(Unit) {
        snapshotFlow { positionMs to (simplifiedPreview && !playing && !loading) }
            .collect { composedTick.value = composedTick.value + 1 }
    }
    // …and when the project is edited (transition added, duration/strength/colour changed).
    LaunchedEffect(Unit) {
        vm.history.map { it.current }.distinctUntilChanged().debounce(120)
            .collect { composedTick.value = composedTick.value + 1 }
    }
    // Render newest-wins: a StateFlow already hands a slow collector only the latest value, so while one
    // frame is decoding, intermediate tick bumps collapse — a fast scrub never backs up and an older
    // frame can't overwrite a newer one. Reads the current state at render time so it targets the latest
    // position.
    LaunchedEffect(Unit) {
        composedTick.collect {
            composedFrame = if (simplifiedPreview && !playing && !loading) {
                frameRenderer.render(vm.project, positionMs, /* maxShortSidePx = */ 720)?.asImageBitmap()
            } else {
                null
            }
        }
    }
    // Clear the composed frame immediately when playback starts (don't wait for a render tick).
    LaunchedEffect(playing) { if (playing) composedFrame = null }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::setMusic) }

    BackHandler {
        when {
            exportState is ExportState.Running -> showExport = true
            selection != null -> selection = null // first Back closes the clip/title/music tools
            // Kept projects are saved automatically; a quick edit of one video asks first.
            !persisted && history.canUndo -> confirmDiscard = true
            else -> onClose()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Top bar
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!persisted && history.canUndo) confirmDiscard = true else onClose() }) { Icon(Icons.Filled.Close, "Close") }
            Column(Modifier.weight(1f).clickable(enabled = persisted) { renaming = true }.padding(horizontal = 4.dp)) {
                Text(doc?.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (persisted) Text("Saved automatically", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = vm::undo, enabled = history.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = vm::redo, enabled = history.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            Button(onClick = { showExport = true }, enabled = !loading && project.clips.isNotEmpty()) { Text("Export") }
        }

        if (missing.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${missing.size} source file(s) can't be found (deleted, moved or access removed).",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::removeMissing) { Text("Remove them") }
                }
            }
        }

        // Always-visible indicator so it's clear when Safe preview mode is on, with a one-tap way off.
        if (safeMode) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Safe preview mode is on — live effects are off; they still apply on export.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        safeMode = false
                        recoveryActive = false
                        com.shaneblackburn.progallery.CrashReporter.setSafePreviewManual(false)
                    }) { Text("Turn off") }
                }
            }
        }

        // Preview
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator()
            else {
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.composition_player_view, FrameLayout(ctx), false) as PlayerView)
                            .also { preview.attach(it) }
                    },
                    onRelease = { preview.attach(null) },
                    modifier = Modifier.fillMaxSize(),
                )
                // Accurate composed frame (effects + transition) over the raw player while paused/scrubbing.
                composedFrame?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                previewError?.let {
                    if (simplifiedPreview) {
                        // While the composed frame is shown (paused/scrub), effects ARE visible, so the
                        // banner turns from an amber warning into a neutral note; during raw playback it
                        // stays amber. Tapping it still shows why the live full-effects preview fell back.
                        val composedShown = composedFrame != null
                        val bannerText = if (composedShown)
                            "Preview (paused) shows the composed frame — playback is raw; effects apply on export."
                        else it
                        Text(
                            bannerText + (if (fallbackReason != null) "  ⓘ Details" else ""),
                            color = Color.White, textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                                .background(if (composedShown) Color(0xCC1B5E20) else Color(0xCC5B4300))
                                .clickable(enabled = fallbackReason != null) { showFallbackDetails = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    } else {
                        // Hard failure: centred message over black.
                        Text(
                            it, color = Color.White, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp).background(Color.Black.copy(alpha = 0.6f)).padding(12.dp),
                        )
                    }
                }
                if (showSyncDebug) {
                    val loc = project.locate(positionMs)
                    val delta = positionMs - playerPosMs
                    val locLine = loc?.let {
                        "clip ${it.clipIndex + 1}/${project.clips.size}  src ${"%.2f".format(it.sourceMs / 1000.0)}s" +
                            "  off ${"%.2f".format(it.offsetInClipMs / 1000.0)}s"
                    } ?: "no clip"
                    Text(
                        "SYNC  pos ${"%.2f".format(positionMs / 1000.0)}s   play ${"%.2f".format(playerPosMs / 1000.0)}s" +
                            "   Δ ${"%+.2f".format(delta / 1000.0)}s\n" +
                            "${if (simplifiedPreview) "SIMPLE" else "FULL"}${if (safeMode) " (safe)" else ""}  $locLine",
                        color = Color(0xFF66FF99), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        // Transport row.  Frame-step nudges the playhead by exactly one frame for precise cuts.
        val seekTo: (Long) -> Unit = { ms ->
            positionMs = ms.coerceIn(0, maxOf(0, project.durationMs))
            preview.seekTo(positionMs)
            scope.launch { scrollToMs(positionMs) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (preview.isPlaying) preview.pause() else {
                    if (positionMs >= project.durationMs - 50) preview.seekTo(0)
                    preview.play()
                }
            }, modifier = Modifier.size(44.dp)) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
            IconButton(onClick = { if (!playing) seekTo(project.stepFrames(positionMs, -1)) }, enabled = !playing, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ChevronLeft, "Previous frame")
            }
            IconButton(onClick = { if (!playing) seekTo(project.stepFrames(positionMs, 1)) }, enabled = !playing, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ChevronRight, "Next frame")
            }
            Text("${Formatters.timecode(positionMs)} / ${Formatters.timecode(project.durationMs)}",
                style = MaterialTheme.typography.labelMedium, maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 2.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onLongPress = { showSyncDebug = !showSyncDebug })
                    })
            // Split at the playhead — always available, whatever is selected.
            IconButton(onClick = {
                val before = project
                vm.commit { it.splitAt(positionMs) }
                val after = vm.project
                if (after == before) {
                    Toast.makeText(context, "Move the playhead inside a clip to split", Toast.LENGTH_SHORT).show()
                } else {
                    after.locate(positionMs)?.let { selection = TimelineSelection.ClipSel(it.clipIndex) }
                }
            }, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.ContentCut, "Split at playhead") }
            IconButton(onClick = { pxPerMs = (pxPerMs / 1.5f).coerceAtLeast(0.005f) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ZoomOut, "Zoom timeline out") }
            IconButton(onClick = { pxPerMs = (pxPerMs * 1.5f).coerceAtMost(0.6f) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ZoomIn, "Zoom timeline in") }
        }

        // Timeline
        VideoTimeline(
            project = project,
            thumbs = thumbs,
            scroll = scroll,
            pxPerMs = pxPerMs,
            selection = selection,
            onSelect = { selection = it },
            onTrimStart = vm::beginChange,
            onTrim = { i, s, e -> vm.previewChange { p -> p.updateClip(i) { it.withTrim(s, e) } } },
            onMove = { from, to ->
                vm.commit { it.moveClip(from, to) }
                selection = TimelineSelection.ClipSel(to)
            },
            modifier = Modifier.padding(vertical = 6.dp),
        )

        // Tools
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 130.dp, max = 250.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            when (val sel = selection) {
                is TimelineSelection.ClipSel -> project.clips.getOrNull(sel.index)?.let { clip ->
                    ClipTools(
                        clip = clip,
                        index = sel.index,
                        clipCount = project.clips.size,
                        onSplit = {
                            val before = project
                            vm.commit { it.splitAt(positionMs) }
                            if (vm.project == before) Toast.makeText(context, "Move the playhead inside the clip to split", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = {
                            if (project.clips.size == 1) Toast.makeText(context, "A project needs at least one clip", Toast.LENGTH_SHORT).show()
                            else { vm.commit { it.removeClip(sel.index) }; selection = null }
                        },
                        onMove = { dir ->
                            val to = sel.index + dir
                            vm.commit { it.moveClip(sel.index, to) }
                            if (to in project.clips.indices) selection = TimelineSelection.ClipSel(to)
                        },
                        onStart = vm::beginChange,
                        onPreview = { change -> vm.previewChange { p -> p.updateClip(sel.index, change) } },
                        onCommit = { change -> vm.commit { p -> p.updateClip(sel.index, change) } },
                    )
                }
                is TimelineSelection.TransitionSel -> TransitionTools(
                    current = project.transitionAfter(sel.afterIndex),
                    aName = project.clips.getOrNull(sel.afterIndex)?.name ?: "Clip ${sel.afterIndex + 1}",
                    bName = project.clips.getOrNull(sel.afterIndex + 1)?.name ?: "Clip ${sel.afterIndex + 2}",
                    onSet = { t -> vm.commit { it.setTransition(sel.afterIndex, t) } },
                    endOfA = project.clips.getOrNull(sel.afterIndex)?.uri?.let { thumbs[it]?.lastOrNull() },
                    startOfB = project.clips.getOrNull(sel.afterIndex + 1)?.uri?.let { thumbs[it]?.firstOrNull() },
                )
                is TimelineSelection.TextSel -> project.texts.firstOrNull { it.id == sel.id }?.let { t ->
                    TextTools(
                        text = t,
                        projectDurationMs = project.durationMs,
                        onEdit = { textEdit = t },
                        onDelete = { vm.commit { p -> p.copy(texts = p.texts.filterNot { it.id == t.id }) }; selection = null },
                        onStart = vm::beginChange,
                        onPreview = { nt -> vm.previewChange { p -> p.copy(texts = p.texts.map { if (it.id == nt.id) nt else it }) } },
                        onCommit = { nt -> vm.commit { p -> p.copy(texts = p.texts.map { if (it.id == nt.id) nt else it }) } },
                    )
                }
                TimelineSelection.MusicSel -> project.music?.let { m ->
                    MusicTools(
                        music = m,
                        onReplace = { pickMusic.launch(arrayOf("audio/*")) },
                        onRemove = { vm.commit { it.copy(music = null) }; selection = null },
                        onStart = vm::beginChange,
                        onPreview = { nm -> vm.previewChange { it.copy(music = nm) } },
                        onCommit = { nm -> vm.commit { it.copy(music = nm) } },
                    )
                }
                null -> ProjectTools(
                    project = project,
                    onAddClip = { showPicker = true },
                    onAddText = {
                        val start = positionMs.coerceIn(0, maxOf(0, project.durationMs - 500))
                        textEdit = TimedText(0L, "", start, minOf(project.durationMs, start + 3000))
                    },
                    onMusic = { pickMusic.launch(arrayOf("audio/*")) },
                    onCommit = vm::commit,
                    safePreview = safeMode,
                    onSafePreview = { on ->
                        safeMode = on
                        recoveryActive = false // a manual choice ends any auto-recovery trial
                        com.shaneblackburn.progallery.CrashReporter.setSafePreviewManual(on)
                    },
                )
            }
        }
    }

    // ---- Dialogs
    textEdit?.let { t ->
        TimedTextDialog(
            initial = t.text,
            onDismiss = { textEdit = null },
            onConfirm = { s ->
                if (t.id == 0L) {
                    val id = System.nanoTime()
                    vm.commit { p -> p.copy(texts = p.texts + t.copy(id = id, text = s)) }
                    selection = TimelineSelection.TextSel(id)
                } else vm.commit { p -> p.copy(texts = p.texts.map { if (it.id == t.id) it.copy(text = s) else it }) }
                textEdit = null
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Keep this video project?") },
            text = { Text("Keep it in Library › Video projects to carry on later, or discard your changes. The original video is never changed.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.keepProject(); onClose() }) { Text("Keep project") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
                    TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    if (showPicker) {
        MediaPickerDialog(
            items = pickerItems,
            title = "Add photos & videos",
            confirmLabel = "Add",
            onDismiss = { showPicker = false },
            onConfirm = { picked -> showPicker = false; vm.addMedia(picked) },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(doc?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename project") },
            text = { androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.rename(name); renaming = false }, enabled = name.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (showFallbackDetails) {
        AlertDialog(
            onDismissRequest = { showFallbackDetails = false },
            title = { Text("About the preview") },
            text = {
                Column {
                    Text("Your edits (colour, speed, transitions and effects) will still be applied when you export. The live preview is showing the raw footage because the effects preview couldn't run on this device for this video.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("Technical reason (for the developer):", style = MaterialTheme.typography.labelMedium)
                    Text(fallbackReason ?: "unknown", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showFallbackDetails = false }) { Text("OK") } },
        )
    }
    if (showExport) {
        when (val s = exportState) {
            is ExportState.Running -> AlertDialog(
                onDismissRequest = { showExport = false },
                title = { Text("Exporting…") },
                text = {
                    Column {
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${(s.progress * 100).roundToInt()}% · you can leave this screen; export continues in the background.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { showExport = false }) { Text("Hide") } },
                dismissButton = { TextButton(onClick = { vm.export.cancel() }) { Text("Cancel export", color = MaterialTheme.colorScheme.error) } },
            )
            is ExportState.Done -> {
                LaunchedEffect(s) {
                    Toast.makeText(context, "Saved ${s.title}", Toast.LENGTH_LONG).show()
                    vm.export.acknowledge(); showExport = false; onClose()
                }
            }
            is ExportState.Failed -> AlertDialog(
                onDismissRequest = { vm.export.acknowledge(); showExport = false },
                title = { Text("Export failed") },
                text = { Text(s.message) },
                confirmButton = { TextButton(onClick = { vm.export.acknowledge(); showExport = false }) { Text("OK") } },
            )
            ExportState.Cancelled -> LaunchedEffect(Unit) { vm.export.acknowledge(); showExport = false }
            ExportState.Idle -> ExportSettingsDialog(
                project = project,
                settings = settings,
                onChange = { settings = it },
                onDismiss = { showExport = false },
                onExport = {
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    preview.pause()
                    vm.startExport(settings)
                },
            )
        }
    }
}
