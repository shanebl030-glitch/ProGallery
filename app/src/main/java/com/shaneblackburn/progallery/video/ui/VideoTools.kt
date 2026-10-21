package com.shaneblackburn.progallery.video.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.format.Formatters
import com.shaneblackburn.progallery.core.video.AspectMode
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Fade
import com.shaneblackburn.progallery.core.video.MusicTrack
import com.shaneblackburn.progallery.core.video.TextAnimation
import com.shaneblackburn.progallery.core.video.TimedText
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.VideoFilter
import com.shaneblackburn.progallery.core.video.VideoProject
import com.shaneblackburn.progallery.editor.ui.ChipRow
import com.shaneblackburn.progallery.editor.ui.ColorSwatches
import com.shaneblackburn.progallery.editor.ui.ValueSlider
import com.shaneblackburn.progallery.video.ExportCodec
import com.shaneblackburn.progallery.video.ExportQuality
import com.shaneblackburn.progallery.video.ExportResolution
import com.shaneblackburn.progallery.video.ProjectComposer
import com.shaneblackburn.progallery.video.VideoExportSettings
import java.util.Locale
import kotlin.math.roundToInt

private enum class ClipTab(val label: String) { EDIT("Edit"), SPEED("Speed"), AUDIO("Audio"), COLOUR("Colour"), FILTER("Filter") }

@Composable
fun ClipTools(
    clip: Clip,
    index: Int,
    clipCount: Int,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
    onStart: () -> Unit,
    onPreview: ((Clip) -> Clip) -> Unit,
    onCommit: ((Clip) -> Clip) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ClipTab.EDIT) }
    Column {
        ChipRow {
            ClipTab.entries.forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) }) }
        }
        when (tab) {
            ClipTab.EDIT -> Column {
                Text(
                    "Drag the clip's edges to trim · ${Formatters.timecode(clip.trimStartMs)}–${Formatters.timecode(clip.trimEndMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ToolButton("Split", Icons.Outlined.ContentCut, onClick = onSplit)
                    ToolButton("Rotate", Icons.Outlined.RotateRight) { onCommit { it.copy(quarterTurns = (it.quarterTurns + 1) % 4) } }
                    ToolButton("Flip", Icons.Outlined.Flip) { onCommit { it.copy(flipH = !it.flipH) } }
                    ToolButton("Flip V", Icons.Outlined.Flip, rotation = 90f) { onCommit { it.copy(flipV = !it.flipV) } }
                    ToolButton("Earlier", Icons.AutoMirrored.Outlined.ArrowBack, enabled = index > 0) { onMove(-1) }
                    ToolButton("Later", Icons.AutoMirrored.Outlined.ArrowForward, enabled = index < clipCount - 1) { onMove(1) }
                    ToolButton("Delete", Icons.Outlined.Delete, onClick = onDelete)
                }
            }
            ClipTab.SPEED -> Column {
                ChipRow {
                    listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f).forEach { sp ->
                        FilterChip(selected = clip.speed == sp, onClick = { onCommit { it.copy(speed = sp) } },
                            label = { Text(if (sp == 1f) "1× normal" else "${trim(sp)}×") })
                    }
                }
                Text(
                    "Slow motion and fast motion keep the voice pitch natural. Clip length: ${Formatters.timecode(clip.timelineDurationMs)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            ClipTab.AUDIO -> Column {
                if (!clip.hasAudio) {
                    Text("This clip has no sound.", modifier = Modifier.padding(16.dp))
                } else {
                    Row(Modifier.clickable { onCommit { it.copy(muted = !it.muted) } }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(clip.muted, onCheckedChange = { m -> onCommit { it.copy(muted = m) } })
                        Text("Mute original sound")
                    }
                    if (!clip.muted) {
                        ValueSlider("Volume", clip.volume, 0f..2f, onStart,
                            onChange = { v -> onPreview { it.copy(volume = v) } }, onReset = { onCommit { it.copy(volume = 1f) } },
                            format = { v -> "${(v * 100).roundToInt()}%" })
                    }
                }
            }
            ClipTab.COLOUR -> Column {
                val c = clip.color
                ValueSlider("Brightness", c.brightness, -1f..1f, onStart, { v -> onPreview { it.copy(color = it.color.copy(brightness = v)) } }, { onCommit { it.copy(color = it.color.copy(brightness = 0f)) } })
                ValueSlider("Contrast", c.contrast, -1f..1f, onStart, { v -> onPreview { it.copy(color = it.color.copy(contrast = v)) } }, { onCommit { it.copy(color = it.color.copy(contrast = 0f)) } })
                ValueSlider("Saturation", c.saturation, -1f..1f, onStart, { v -> onPreview { it.copy(color = it.color.copy(saturation = v)) } }, { onCommit { it.copy(color = it.color.copy(saturation = 0f)) } })
                ValueSlider("Warmth", c.temperature, -1f..1f, onStart, { v -> onPreview { it.copy(color = it.color.copy(temperature = v)) } }, { onCommit { it.copy(color = it.color.copy(temperature = 0f)) } })
            }
            ClipTab.FILTER -> ChipRow {
                VideoFilter.entries.forEach { f ->
                    FilterChip(selected = clip.filter == f, onClick = { onCommit { it.copy(filter = f) } }, label = { Text(f.label) })
                }
            }
        }
    }
}

private fun trim(f: Float) = if (f % 1f == 0f) f.toInt().toString() else f.toString()

@Composable
private fun ToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    rotation: Float = 0f,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.rotate(rotation))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun TransitionTools(current: Transition, onSet: (Transition) -> Unit) {
    Column {
        Text("Transition between clips", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 8.dp))
        ChipRow {
            TransitionType.entries.forEach { t ->
                FilterChip(selected = current.type == t,
                    onClick = { onSet(if (t == TransitionType.NONE) Transition.NONE else Transition(t, current.durationMs.takeIf { it > 0 } ?: 600)) },
                    label = { Text(t.label) })
            }
        }
        if (current.type != TransitionType.NONE) {
            ChipRow {
                listOf(300L, 600L, 1000L, 1500L).forEach { d ->
                    FilterChip(selected = current.durationMs == d, onClick = { onSet(current.copy(durationMs = d)) },
                        label = { Text(String.format(Locale.ROOT, "%.1f s", d / 1000f)) })
                }
            }
        }
    }
}

@Composable
fun TextTools(
    text: TimedText,
    projectDurationMs: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStart: () -> Unit,
    onPreview: (TimedText) -> Unit,
    onCommit: (TimedText) -> Unit,
) {
    Column {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton("Edit", Icons.Outlined.Edit, onClick = onEdit)
            ToolButton("Delete", Icons.Outlined.Delete, onClick = onDelete)
            FilterChip(selected = text.background, onClick = { onCommit(text.copy(background = !text.background)) }, label = { Text("Background") })
        }
        ChipRow {
            TextAnimation.entries.forEach { a ->
                FilterChip(selected = text.animation == a, onClick = { onCommit(text.copy(animation = a)) }, label = { Text(a.label) })
            }
        }
        ColorSwatches(text.color, onPick = { onCommit(text.copy(color = it)) })
        val maxT = projectDurationMs.toFloat().coerceAtLeast(1f)
        ValueSlider("Starts", text.startMs.toFloat(), 0f..maxT, onStart,
            onChange = { v -> onPreview(text.copy(startMs = v.toLong(), endMs = maxOf(text.endMs, v.toLong() + 300))) },
            onReset = { onCommit(text.copy(startMs = 0)) }, format = { Formatters.timecode(it.toLong()) })
        ValueSlider("Ends", text.endMs.toFloat(), 0f..maxT, onStart,
            onChange = { v -> onPreview(text.copy(endMs = maxOf(v.toLong(), text.startMs + 300))) },
            onReset = { onCommit(text.copy(endMs = projectDurationMs)) }, format = { Formatters.timecode(it.toLong()) })
        ValueSlider("Size", text.size, 0.02f..0.2f, onStart, { v -> onPreview(text.copy(size = v)) }, { onCommit(text.copy(size = 0.06f)) })
        ValueSlider("Vertical position", text.y, 0.05f..0.95f, onStart, { v -> onPreview(text.copy(y = v)) }, { onCommit(text.copy(y = 0.85f)) })
    }
}

@Composable
fun MusicTools(
    music: MusicTrack,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
    onStart: () -> Unit,
    onPreview: (MusicTrack) -> Unit,
    onCommit: (MusicTrack) -> Unit,
) {
    Column {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton("Replace", Icons.Outlined.LibraryMusic, onClick = onReplace)
            ToolButton("Remove", Icons.Outlined.Delete, onClick = onRemove)
            Row(Modifier.clickable { onCommit(music.copy(loop = !music.loop)) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(music.loop, { onCommit(music.copy(loop = it)) }); Text("Loop")
            }
        }
        ValueSlider("Music volume", music.volume, 0f..2f, onStart, { v -> onPreview(music.copy(volume = v)) }, { onCommit(music.copy(volume = 0.8f)) },
            format = { v -> "${(v * 100).roundToInt()}%" })
        ValueSlider("Start from", music.startOffsetMs.toFloat(), 0f..music.durationMs.toFloat().coerceAtLeast(1f), onStart,
            { v -> onPreview(music.copy(startOffsetMs = v.toLong())) }, { onCommit(music.copy(startOffsetMs = 0)) },
            format = { Formatters.timecode(it.toLong()) })
        ValueSlider("Fade in", music.fadeInMs.toFloat(), 0f..5000f, onStart, { v -> onPreview(music.copy(fadeInMs = v.toLong())) },
            { onCommit(music.copy(fadeInMs = 0)) }, format = { String.format(Locale.ROOT, "%.1f s", it / 1000f) })
        ValueSlider("Fade out", music.fadeOutMs.toFloat(), 0f..5000f, onStart, { v -> onPreview(music.copy(fadeOutMs = v.toLong())) },
            { onCommit(music.copy(fadeOutMs = 0)) }, format = { String.format(Locale.ROOT, "%.1f s", it / 1000f) })
    }
}

@Composable
fun ProjectTools(
    project: VideoProject,
    onAddClip: () -> Unit,
    onAddText: () -> Unit,
    onMusic: () -> Unit,
    onCommit: ((VideoProject) -> VideoProject) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            ToolButton("Add clip", Icons.Outlined.Add, onClick = onAddClip)
            ToolButton("Text", Icons.Outlined.TextFields, onClick = onAddText)
            ToolButton(if (project.music == null) "Music" else "Change music", Icons.Outlined.LibraryMusic, onClick = onMusic)
        }
        ChipRow {
            Text("Frame", style = MaterialTheme.typography.labelLarge)
            AspectMode.entries.forEach { a ->
                FilterChip(selected = project.aspect == a, onClick = { onCommit { it.copy(aspect = a) } }, label = { Text(a.label) })
            }
        }
        ChipRow {
            FilterChip(selected = project.fadeIn != null,
                onClick = { onCommit { it.copy(fadeIn = if (it.fadeIn == null) Fade(800) else null) } }, label = { Text("Fade in from black") })
            FilterChip(selected = project.fadeOut != null,
                onClick = { onCommit { it.copy(fadeOut = if (it.fadeOut == null) Fade(1200) else null) } }, label = { Text("Fade out to black") })
            FilterChip(selected = project.fadeOut?.toWhite == true,
                onClick = { onCommit { it.copy(fadeOut = if (it.fadeOut?.toWhite == true) null else Fade(1200, toWhite = true)) } },
                label = { Text("Fade out to white") })
        }
        Text("Tap a clip to edit it · tap the dot between clips for a transition · scroll the timeline to move the playhead",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
fun TimedTextDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isEmpty()) "Add title" else "Edit text") },
        text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), maxLines = 4) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ExportSettingsDialog(
    project: VideoProject,
    settings: VideoExportSettings,
    onChange: (VideoExportSettings) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    val (w, h) = ProjectComposer.outputSize(project, settings)
    val bitrate = ProjectComposer.bitrate(w, h, settings.frameRate, settings.quality, settings.codec)
    val estBytes = (bitrate.toLong() + 192_000L) / 8 * project.durationMs / 1000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export video") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Label("Resolution")
                ChipRow {
                    ExportResolution.entries.forEach { r ->
                        FilterChip(selected = settings.resolution == r, onClick = { onChange(settings.copy(resolution = r)) }, label = { Text(r.label) })
                    }
                }
                Label("Frame rate")
                ChipRow {
                    listOf(0 to "Original", 60 to "60", 30 to "30", 24 to "24").forEach { (f, l) ->
                        FilterChip(selected = settings.frameRate == f, onClick = { onChange(settings.copy(frameRate = f)) }, label = { Text(l) })
                    }
                }
                Label("Quality")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ExportQuality.entries.forEachIndexed { i, q ->
                        SegmentedButton(settings.quality == q, { onChange(settings.copy(quality = q)) },
                            SegmentedButtonDefaults.itemShape(i, ExportQuality.entries.size)) { Text(q.label, maxLines = 1) }
                    }
                }
                Label("Format")
                ExportCodec.entries.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable { onChange(settings.copy(codec = c)) }, verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(settings.codec == c, { onChange(settings.copy(codec = c)) })
                        Text(c.label)
                    }
                }
                Text(
                    "$w × $h · ${Formatters.duration(project.durationMs)} · about ${Formatters.fileSize(estBytes)}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    "Saved as a new video next to the original. Frame rate can only be lowered.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onExport) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}
