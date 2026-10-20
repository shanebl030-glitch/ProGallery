package com.shaneblackburn.progallery.editor.ui

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FilterVintage
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WebAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shaneblackburn.progallery.core.edit.Overlay
import com.shaneblackburn.progallery.core.edit.Pt
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.editor.EditorLoad
import com.shaneblackburn.progallery.editor.EditorViewModel
import com.shaneblackburn.progallery.editor.ExportOptions
import com.shaneblackburn.progallery.editor.SaveState
import com.shaneblackburn.progallery.ui.theme.ProGalleryTheme
import com.shaneblackburn.progallery.ui.theme.ThemeMode
import kotlin.math.max
import kotlin.math.min

enum class EditorTool(val label: String, val icon: ImageVector) {
    CROP("Crop", Icons.Outlined.Crop),
    ADJUST("Adjust", Icons.Outlined.Tune),
    FILTERS("Filters", Icons.Outlined.FilterVintage),
    CURVES("Curves", Icons.Outlined.ShowChart),
    COLOR("Colour", Icons.Outlined.Palette),
    TEXT("Text", Icons.Outlined.TextFields),
    DRAW("Draw", Icons.Outlined.Brush),
    STICKERS("Stickers", Icons.Outlined.EmojiEmotions),
    FRAME("Frame", Icons.Outlined.WebAsset),
}

private val OVERLAY_TOOLS = setOf(EditorTool.TEXT, EditorTool.DRAW, EditorTool.STICKERS)

/** Full-screen photo editor. Always dark, like most pro editors, so colours read accurately. */
@Composable
fun EditorScreen(item: MediaItem, onClose: () -> Unit, onSaved: (Uri?) -> Unit) {
    ProGalleryTheme(mode = ThemeMode.DARK, dynamicColor = false) {
        Surface(color = Color(0xFF0E0E10), modifier = Modifier.fillMaxSize()) {
            EditorContent(item, onClose, onSaved)
        }
    }
}

@Composable
private fun EditorContent(item: MediaItem, onClose: () -> Unit, onSaved: (Uri?) -> Unit) {
    val vm: EditorViewModel = viewModel()
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val load by vm.load.collectAsState()
    val history by vm.history.collectAsState()
    val preview by vm.preview.collectAsState()
    val original by vm.original.collectAsState()
    val cropBase by vm.cropBase.collectAsState()
    val rendering by vm.rendering.collectAsState()
    val save by vm.save.collectAsState()
    val state = history.current

    var tool by rememberSaveable { mutableStateOf(EditorTool.ADJUST) }
    var comparing by remember { mutableStateOf(false) }
    var selectedOverlay by remember { mutableStateOf<Long?>(null) }
    var drawSettings by remember { mutableStateOf(DrawSettings()) }
    var aspect by remember { mutableStateOf(ASPECTS[0]) }
    var textDialog by remember { mutableStateOf<Overlay.Text?>(null) }
    var saveMenu by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(ExportOptions()) }

    LaunchedEffect(item.id) {
        val screenPx = with(density) { max(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }
        vm.open(item, previewMaxSide = min(2048, max(1080, screenPx)))
    }
    LaunchedEffect(tool) {
        vm.cropMode = tool == EditorTool.CROP
        vm.overlaysLive = tool in OVERLAY_TOOLS
        if (tool !in OVERLAY_TOOLS) selectedOverlay = null
    }
    DisposableEffect(Unit) { onDispose { vm.cropMode = false; vm.overlaysLive = false } }

    LaunchedEffect(save) {
        when (val s = save) {
            is SaveState.Saved -> {
                Toast.makeText(context, if (s.replaced) "Original replaced" else "Saved as a copy", Toast.LENGTH_SHORT).show()
                vm.consumeSaveResult()
                onSaved(s.uri)
            }
            is SaveState.Failed -> {
                Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
                vm.consumeSaveResult()
            }
            else -> Unit
        }
    }

    val writeConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.replaceOriginal(options)
        else Toast.makeText(context, "Original not changed", Toast.LENGTH_SHORT).show()
    }
    fun requestReplace() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sender = MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(item.uri))).intentSender
            writeConsent.launch(IntentSenderRequest.Builder(sender).build())
        } else {
            vm.replaceOriginal(options)
        }
    }

    BackHandler {
        when {
            save is SaveState.Saving -> vm.cancelSave()
            vm.hasChanges -> confirmDiscard = true
            else -> onClose()
        }
    }

    // Overlay helpers
    val overlays = state.overlays
    fun updateOverlay(o: Overlay, live: Boolean) {
        val change: (com.shaneblackburn.progallery.core.edit.EditState) -> com.shaneblackburn.progallery.core.edit.EditState =
            { s -> s.copy(overlays = s.overlays.map { if (it.id == o.id) o else it }) }
        if (live) vm.previewChange(change) else vm.commit(change)
    }
    fun removeOverlay(id: Long) { vm.commit { s -> s.copy(overlays = s.overlays.filterNot { it.id == id }) }; selectedOverlay = null }
    fun newId() = System.nanoTime()

    Column(Modifier.fillMaxSize()) {
        // ---- Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (vm.hasChanges) confirmDiscard = true else onClose() }) { Icon(Icons.Filled.Close, "Close editor") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = vm::undo, enabled = history.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = vm::redo, enabled = history.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            Box(
                Modifier
                    .size(48.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            comparing = true
                            waitForUpOrCancellation()
                            comparing = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Compare, "Hold to compare with original") }
            Spacer(Modifier.weight(1f))
            Box {
                Button(onClick = { saveMenu = true }, enabled = load is EditorLoad.Ready && vm.hasChanges && save !is SaveState.Saving) {
                    Text("Save")
                }
                DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                    DropdownMenuItem(text = { Text("Save as copy") }, onClick = { saveMenu = false; vm.saveCopy(options) })
                    if (vm.canReplace()) {
                        DropdownMenuItem(text = { Text("Replace original…") }, onClick = { saveMenu = false; confirmReplace = true })
                    }
                    DropdownMenuItem(text = { Text("Export options…") }, onClick = { saveMenu = false; exportDialog = true })
                }
            }
        }

        // ---- Canvas
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            when (val l = load) {
                EditorLoad.Loading -> CircularProgressIndicator()
                is EditorLoad.Failed -> Text(l.message, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                is EditorLoad.Ready -> {
                    val shown = if (comparing) original else preview
                    when {
                        comparing && shown != null ->
                            Image(shown, "Original", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        tool == EditorTool.CROP && cropBase != null -> {
                            val base = cropBase!!
                            val ratio = if (aspect.original) {
                                val (ow, oh) = state.geometry.orientedSize(l.width, l.height); ow.toFloat() / oh
                            } else aspect.ratio
                            CropOverlay(
                                image = base,
                                crop = state.geometry.crop,
                                aspect = ratio,
                                onCropChange = { c -> vm.previewChange { s -> s.copy(geometry = s.geometry.copy(crop = c)) } },
                                onGestureStart = vm::beginChange,
                            )
                        }
                        tool in OVERLAY_TOOLS && preview != null -> OverlayCanvas(
                            image = preview!!,
                            overlays = overlays,
                            selectedId = selectedOverlay,
                            mode = when {
                                tool == EditorTool.DRAW && drawSettings.shape != null -> OverlayMode.SHAPE
                                tool == EditorTool.DRAW -> OverlayMode.DRAW
                                else -> OverlayMode.SELECT
                            },
                            onSelect = { selectedOverlay = it },
                            onGestureStart = vm::beginChange,
                            onUpdate = { updateOverlay(it, live = true) },
                            onStrokeStart = { p ->
                                val id = newId()
                                vm.previewChange { s ->
                                    s.copy(overlays = s.overlays + Overlay.Stroke(id, listOf(p), drawSettings.color, drawSettings.width, drawSettings.brush))
                                }
                                id
                            },
                            onStrokeAdd = { id, p ->
                                vm.previewChange { s ->
                                    s.copy(overlays = s.overlays.map { if (it.id == id && it is Overlay.Stroke) it.copy(points = it.points + p) else it })
                                }
                            },
                            onShapeStart = { p ->
                                val id = newId()
                                val kind = drawSettings.shape ?: com.shaneblackburn.progallery.core.edit.ShapeKind.LINE
                                vm.previewChange { s ->
                                    s.copy(overlays = s.overlays + Overlay.Shape(id, kind, p, p, drawSettings.color, drawSettings.width / 1.5f, drawSettings.filled))
                                }
                                id
                            },
                            onShapeDrag = { id, p ->
                                vm.previewChange { s -> s.copy(overlays = s.overlays.map { if (it.id == id && it is Overlay.Shape) it.copy(end = p) else it }) }
                            },
                            onDoubleTapText = { textDialog = it },
                        )
                        shown != null -> Image(shown, "Edited photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    if (rendering && !comparing) {
                        LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp))
                    }
                    if (comparing) {
                        Text("Original", color = Color.White, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.5f)).padding(8.dp))
                    }
                }
            }
        }

        // ---- Tool panel
        val ready = load as? EditorLoad.Ready
        Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(top = 4.dp)) {
            if (ready != null) {
                when (tool) {
                    EditorTool.CROP -> CropPanel(state, ready.width, ready.height, aspect, { aspect = it }, vm::beginChange, vm::previewChange, vm::commit)
                    EditorTool.ADJUST -> AdjustPanel(state, vm::beginChange, vm::previewChange, vm::commit)
                    EditorTool.FILTERS -> FiltersPanel(state, original, vm::beginChange, vm::previewChange, vm::commit)
                    EditorTool.CURVES -> CurvesPanel(state, vm::beginChange, vm::previewChange, vm::commit)
                    EditorTool.COLOR -> HslPanel(state, vm::beginChange, vm::previewChange, vm::commit)
                    EditorTool.TEXT -> {
                        val sel = overlays.firstOrNull { it.id == selectedOverlay } as? Overlay.Text
                        TextPanel(
                            selected = sel,
                            onAdd = { textDialog = Overlay.Text(id = 0L, text = "", x = 0.5f, y = 0.5f) },
                            onEdit = { textDialog = it },
                            onDelete = { removeOverlay(it.id) },
                            onStart = vm::beginChange,
                            onUpdatePreview = { updateOverlay(it, live = true) },
                            onUpdate = { updateOverlay(it, live = false) },
                        )
                    }
                    EditorTool.DRAW -> {
                        val drawn = overlays.filter { it is Overlay.Stroke || it is Overlay.Shape }
                        DrawPanel(
                            settings = drawSettings,
                            onSettings = { drawSettings = it },
                            canUndoStroke = drawn.isNotEmpty(),
                            onUndoStroke = { drawn.lastOrNull()?.let { last -> vm.commit { s -> s.copy(overlays = s.overlays.filterNot { it.id == last.id }) } } },
                            onClear = { vm.commit { s -> s.copy(overlays = s.overlays.filterNot { it is Overlay.Stroke || it is Overlay.Shape }) } },
                        )
                    }
                    EditorTool.STICKERS -> StickerPanel(
                        selected = overlays.firstOrNull { it.id == selectedOverlay } as? Overlay.Sticker,
                        onAdd = { g ->
                            val id = newId()
                            vm.commit { s -> s.copy(overlays = s.overlays + Overlay.Sticker(id, g, 0.5f, 0.5f)) }
                            selectedOverlay = id
                        },
                        onDelete = { removeOverlay(it.id) },
                    )
                    EditorTool.FRAME -> FramePanel(state, vm::beginChange, vm::previewChange, vm::commit)
                }
            }
        }

        // ---- Tool tabs
        LazyRow(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(EditorTool.entries, key = { it.name }) { t ->
                Row(Modifier.width(76.dp)) {
                    NavigationBarItem(
                        selected = tool == t,
                        onClick = { tool = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label, maxLines = 1) },
                    )
                }
            }
        }
    }

    // ---- Dialogs and overlays
    textDialog?.let { editing ->
        TextEntryDialog(
            initial = editing.text,
            onDismiss = { textDialog = null },
            onConfirm = { text ->
                if (editing.id == 0L) {
                    val id = newId()
                    vm.commit { s -> s.copy(overlays = s.overlays + editing.copy(id = id, text = text)) }
                    selectedOverlay = id
                } else {
                    updateOverlay(editing.copy(text = text), live = false)
                }
                textDialog = null
            },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard edits?") },
            text = { Text("Your changes to this photo will be lost.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("Replace the original?") },
            text = { Text("The original photo will be overwritten with your edits. This can't be undone. \"Save as copy\" keeps the original.") },
            confirmButton = { TextButton(onClick = { confirmReplace = false; requestReplace() }) { Text("Replace", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }
    if (exportDialog) {
        ExportOptionsDialog(options, onChange = { options = it }, onDismiss = { exportDialog = false })
    }
    (save as? SaveState.Saving)?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Rendering at full resolution", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelSave) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextEntryDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "Add text" else "Edit text") },
        text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 5) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

