package com.shaneblackburn.progallery.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RotateLeft
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaneblackburn.progallery.core.edit.AdjustKey
import com.shaneblackburn.progallery.core.edit.Brush
import com.shaneblackburn.progallery.core.edit.CurvePoint
import com.shaneblackburn.progallery.core.edit.EditState
import com.shaneblackburn.progallery.core.edit.FilterLook
import com.shaneblackburn.progallery.core.edit.FontChoice
import com.shaneblackburn.progallery.core.edit.Frame
import com.shaneblackburn.progallery.core.edit.FrameStyle
import com.shaneblackburn.progallery.core.edit.HslValue
import com.shaneblackburn.progallery.core.edit.HueBand
import com.shaneblackburn.progallery.core.edit.NormRect
import com.shaneblackburn.progallery.core.edit.Overlay
import com.shaneblackburn.progallery.core.edit.PixelPipeline
import com.shaneblackburn.progallery.core.edit.ShapeKind
import com.shaneblackburn.progallery.core.edit.TextAlignChoice
import com.shaneblackburn.progallery.core.edit.ToneCurve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50,
    0xFF00BCD4, 0xFF2196F3, 0xFF3F51B5, 0xFF9C27B0, 0xFFE91E63, 0xFF795548, 0xFF9E9E9E,
).map { it.toInt() }

/** Symbols and emoji drawn with the system emoji font (no bundled artwork). */
val STICKERS = listOf(
    "❤️", "⭐", "✨", "🔥", "😀", "😂", "😍", "😎", "🥳", "👍", "👏", "🙌", "🎉", "🎂", "🎈", "🎁",
    "🌸", "🌻", "🌈", "☀️", "🌙", "⚡", "❄️", "🍀", "🐶", "🐱", "🦋", "🍕", "☕", "🏖️", "⛰️", "📍",
    "✅", "❌", "❗", "❓", "💯", "➡️", "⬅️", "⬆️", "⬇️", "🔴", "🟢", "🔵",
)

// ----------------------------------------------------------------- shared bits

@Composable
fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onStart: () -> Unit,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
    format: (Float) -> String = { v -> "${(v * 100).roundToInt()}" },
) {
    var dragging by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = onReset, enabled = value != (if (range.start < 0f) 0f else range.start)) { Text("Reset") }
        }
        Slider(
            value = value,
            onValueChange = { if (!dragging) { dragging = true; onStart() }; onChange(it) },
            onValueChangeFinished = { dragging = false },
            valueRange = range,
        )
    }
}

@Composable
fun ColorSwatches(selected: Int?, onPick: (Int) -> Unit, modifier: Modifier = Modifier, includeNone: Boolean = false, onNone: () -> Unit = {}) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (includeNone) {
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .border(2.dp, if (selected == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable(onClickLabel = "No colour") { onNone() },
                contentAlignment = Alignment.Center,
            ) { Text("∅", fontSize = 16.sp) }
        }
        SWATCHES.forEach { c ->
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        if (selected == c) 3.dp else 1.dp,
                        if (selected == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .clickable(onClickLabel = "Colour") { onPick(c) },
            )
        }
    }
}

@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

// ----------------------------------------------------------------- crop

data class AspectOption(val label: String, val ratio: Float?, val original: Boolean = false)

val ASPECTS = listOf(
    AspectOption("Free", null), AspectOption("Original", null, original = true),
    AspectOption("1:1", 1f), AspectOption("4:5", 4f / 5f), AspectOption("3:4", 3f / 4f), AspectOption("2:3", 2f / 3f),
    AspectOption("9:16", 9f / 16f), AspectOption("4:3", 4f / 3f), AspectOption("3:2", 3f / 2f), AspectOption("16:9", 16f / 9f),
)

@Composable
fun CropPanel(
    state: EditState,
    imageW: Int,
    imageH: Int,
    aspect: AspectOption,
    onAspect: (AspectOption) -> Unit,
    onStart: () -> Unit,
    onPreview: ((EditState) -> EditState) -> Unit,
    onCommit: ((EditState) -> EditState) -> Unit,
) {
    val g = state.geometry
    val (ow, oh) = g.orientedSize(imageW, imageH)
    Column {
        ChipRow {
            ASPECTS.forEach { opt ->
                FilterChip(
                    selected = aspect == opt,
                    onClick = {
                        onAspect(opt)
                        val ratio = if (opt.original) ow.toFloat() / oh else opt.ratio
                        if (ratio != null) onCommit { it.copy(geometry = it.geometry.copy(crop = NormRect.centredAspect(ratio, ow, oh))) }
                    },
                    label = { Text(opt.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        ValueSlider(
            "Straighten", g.straighten, -45f..45f, onStart,
            onChange = { v -> onPreview { it.copy(geometry = it.geometry.copy(straighten = (v * 10).roundToInt() / 10f)) } },
            onReset = { onCommit { it.copy(geometry = it.geometry.copy(straighten = 0f)) } },
            format = { v -> String.format(java.util.Locale.ROOT, "%.1f°", v) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { onCommit { it.copy(geometry = it.geometry.rotateCcw()) } }) { Icon(Icons.Outlined.RotateLeft, "Rotate left") }
            IconButton(onClick = { onCommit { it.copy(geometry = it.geometry.rotateCw()) } }) { Icon(Icons.Outlined.RotateRight, "Rotate right") }
            IconButton(onClick = { onCommit { it.copy(geometry = it.geometry.flippedH()) } }) { Icon(Icons.Outlined.Flip, "Flip horizontally") }
            IconButton(onClick = { onCommit { it.copy(geometry = it.geometry.flippedV()) } }) {
                Icon(Icons.Outlined.Flip, "Flip vertically", Modifier.rotate(90f))
            }
            TextButton(onClick = { onCommit { it.copy(geometry = com.shaneblackburn.progallery.core.edit.Geometry()) }; onAspect(ASPECTS[0]) }) {
                Text("Reset")
            }
        }
    }
}

// ----------------------------------------------------------------- adjust

private val ADJUST_GROUPS = listOf(
    "Light" to listOf(AdjustKey.EXPOSURE, AdjustKey.BRIGHTNESS, AdjustKey.CONTRAST, AdjustKey.HIGHLIGHTS,
        AdjustKey.SHADOWS, AdjustKey.WHITES, AdjustKey.BLACKS),
    "Colour" to listOf(AdjustKey.SATURATION, AdjustKey.VIBRANCE, AdjustKey.TEMPERATURE, AdjustKey.TINT),
    "Detail" to listOf(AdjustKey.SHARPNESS, AdjustKey.CLARITY, AdjustKey.NOISE_REDUCTION),
    "Effects" to listOf(AdjustKey.VIGNETTE, AdjustKey.BLUR, AdjustKey.GRAIN),
)

@Composable
fun AdjustPanel(
    state: EditState,
    onStart: () -> Unit,
    onPreview: ((EditState) -> EditState) -> Unit,
    onCommit: ((EditState) -> EditState) -> Unit,
) {
    var key by rememberSaveable { mutableStateOf(AdjustKey.EXPOSURE) }
    Column {
        ValueSlider(
            key.label, key.get(state.adjust), key.min..key.max, onStart,
            onChange = { v -> onPreview { it.copy(adjust = key.set(it.adjust, v)) } },
            onReset = { onCommit { it.copy(adjust = key.set(it.adjust, 0f)) } },
        )
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ADJUST_GROUPS.forEach { (group, keys) ->
                item(key = group) {
                    Text(group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 18.dp))
                }
                items(keys, key = { it.name }) { k ->
                    val changed = k.get(state.adjust) != 0f
                    FilterChip(
                        selected = key == k,
                        onClick = { key = k },
                        label = { Text(if (changed) "${k.label} •" else k.label) },
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- filters

@Composable
fun FiltersPanel(
    state: EditState,
    original: ImageBitmap?,
    onStart: () -> Unit,
    onPreview: ((EditState) -> EditState) -> Unit,
    onCommit: ((EditState) -> EditState) -> Unit,
) {
    var thumbs by remember { mutableStateOf<Map<FilterLook, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(original) {
        val src = original?.asAndroidBitmap() ?: return@LaunchedEffect
        thumbs = withContext(Dispatchers.Default) {
            com.shaneblackburn.progallery.editor.FilterThumbnails.render(src).mapValues { it.value.asImageBitmap() }
        }
    }
    Column {
        if (state.filter != FilterLook.NONE) {
            ValueSlider(
                "${state.filter.label} strength", state.filterIntensity, 0f..1f, onStart,
                onChange = { v -> onPreview { it.copy(filterIntensity = v) } },
                onReset = { onCommit { it.copy(filterIntensity = 1f) } },
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(FilterLook.entries, key = { it.name }) { look ->
                val selected = state.filter == look
                Column(
                    Modifier.width(76.dp).clickable(onClickLabel = look.label) {
                        onCommit { it.copy(filter = look, filterIntensity = if (selected) it.filterIntensity else 1f) }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.dp, Color.Transparent),
                                RoundedCornerShape(12.dp)),
                    ) {
                        thumbs[look]?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.aspectRatio(1f)) }
                    }
                    Text(look.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ----------------------------------------------------------------- curves

@Composable
fun CurvesPanel(state: EditState, onStart: () -> Unit, onPreview: ((EditState) -> EditState) -> Unit, onCommit: ((EditState) -> EditState) -> Unit) {
    var channel by rememberSaveable { mutableStateOf(0) } // 0 RGB, 1 R, 2 G, 3 B
    val c = state.curves
    val points = when (channel) { 1 -> c.red; 2 -> c.green; 3 -> c.blue; else -> c.master }
    val color = when (channel) { 1 -> Color(0xFFE53935); 2 -> Color(0xFF43A047); 3 -> Color(0xFF1E88E5); else -> MaterialTheme.colorScheme.primary }
    fun with(p: List<CurvePoint>, s: EditState) = s.copy(curves = when (channel) {
        1 -> s.curves.copy(red = p); 2 -> s.curves.copy(green = p); 3 -> s.curves.copy(blue = p); else -> s.curves.copy(master = p)
    })
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CurveEditor(
            points = points, color = color, gridColor = MaterialTheme.colorScheme.outlineVariant,
            onGestureStart = onStart,
            onChange = { p -> onPreview { with(p, it) } },
            modifier = Modifier.size(150.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
        )
        Column(Modifier.padding(start = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("RGB", "R", "G", "B").forEachIndexed { i, l ->
                    FilterChip(selected = channel == i, onClick = { channel = i }, label = { Text(l) })
                }
            }
            OutlinedButton(onClick = { onCommit { with(ToneCurve.sCurve(0.6f), it) } }) { Text("S-curve") }
            OutlinedButton(onClick = { onCommit { with(listOf(CurvePoint(0f, 0.08f), CurvePoint(1f, 0.95f)), it) } }) { Text("Matte") }
            TextButton(onClick = { onCommit { with(ToneCurve.IDENTITY_POINTS, it) } }) { Text("Reset curve") }
            Text("Tap to add a point · drag to move · double-tap to remove", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ----------------------------------------------------------------- HSL

private fun bandColor(b: HueBand): Color = Color.hsv(b.centreDegrees, 0.8f, 0.95f)

@Composable
fun HslPanel(state: EditState, onStart: () -> Unit, onPreview: ((EditState) -> EditState) -> Unit, onCommit: ((EditState) -> EditState) -> Unit) {
    var band by rememberSaveable { mutableStateOf(HueBand.RED) }
    val v = state.hsl.get(band)
    fun set(s: EditState, nv: HslValue) = s.copy(hsl = s.hsl.with(band, nv))
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            HueBand.entries.forEach { b ->
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(bandColor(b))
                        .border(if (band == b) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        .clickable(onClickLabel = b.label) { band = b },
                )
            }
        }
        ValueSlider("${band.label} hue", v.hue, -1f..1f, onStart, { x -> onPreview { set(it, v.copy(hue = x)) } }, { onCommit { set(it, v.copy(hue = 0f)) } })
        ValueSlider("${band.label} saturation", v.saturation, -1f..1f, onStart, { x -> onPreview { set(it, v.copy(saturation = x)) } }, { onCommit { set(it, v.copy(saturation = 0f)) } })
        ValueSlider("${band.label} luminance", v.luminance, -1f..1f, onStart, { x -> onPreview { set(it, v.copy(luminance = x)) } }, { onCommit { set(it, v.copy(luminance = 0f)) } })
    }
}

// ----------------------------------------------------------------- text

@Composable
fun TextPanel(
    selected: Overlay.Text?,
    onAdd: () -> Unit,
    onEdit: (Overlay.Text) -> Unit,
    onDelete: (Overlay.Text) -> Unit,
    onStart: () -> Unit,
    onUpdatePreview: (Overlay.Text) -> Unit,
    onUpdate: (Overlay.Text) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onAdd) { Icon(Icons.Outlined.Add, null); Text(" Add text") }
            if (selected != null) {
                IconButton(onClick = { onEdit(selected) }) { Icon(Icons.Outlined.Edit, "Edit text") }
                IconButton(onClick = { onDelete(selected) }) { Icon(Icons.Outlined.Delete, "Delete text") }
                IconButton(onClick = { onUpdate(selected.copy(align = TextAlignChoice.LEFT)) }) { Icon(Icons.AutoMirrored.Filled.FormatAlignLeft, "Align left") }
                IconButton(onClick = { onUpdate(selected.copy(align = TextAlignChoice.CENTER)) }) { Icon(Icons.Filled.FormatAlignCenter, "Centre") }
                IconButton(onClick = { onUpdate(selected.copy(align = TextAlignChoice.RIGHT)) }) { Icon(Icons.AutoMirrored.Filled.FormatAlignRight, "Align right") }
            }
        }
        if (selected == null) {
            Text("Add text, then drag to move, pinch to resize and rotate. Double-tap text to edit it.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp))
            return@Column
        }
        ChipRow {
            FontChoice.entries.forEach { f ->
                FilterChip(selected = selected.font == f, onClick = { onUpdate(selected.copy(font = f)) }, label = { Text(f.label) })
            }
            FilterChip(selected = selected.outlineColor != null, onClick = {
                onUpdate(selected.copy(outlineColor = if (selected.outlineColor == null) (if (selected.color == SWATCHES[1]) SWATCHES[0] else SWATCHES[1]) else null))
            }, label = { Text("Outline") })
            FilterChip(selected = selected.shadow, onClick = { onUpdate(selected.copy(shadow = !selected.shadow)) }, label = { Text("Shadow") })
            FilterChip(selected = selected.backgroundColor != null, onClick = {
                onUpdate(selected.copy(backgroundColor = if (selected.backgroundColor == null) 0xAA000000.toInt() else null))
            }, label = { Text("Background") })
        }
        ColorSwatches(selected.color, onPick = { onUpdate(selected.copy(color = it)) })
        ValueSlider("Size", selected.size, 0.02f..0.4f, onStart,
            onChange = { v -> onUpdatePreview(selected.copy(size = v)) }, onReset = { onUpdate(selected.copy(size = 0.08f)) },
            format = { v -> "${(v * 100).roundToInt()}" })
    }
}

// ----------------------------------------------------------------- draw

data class DrawSettings(
    val brush: Brush = Brush.PEN,
    val shape: ShapeKind? = null,
    val color: Int = 0xFFE53935.toInt(),
    val width: Float = 0.012f,
    val filled: Boolean = false,
)

@Composable
fun DrawPanel(
    settings: DrawSettings,
    onSettings: (DrawSettings) -> Unit,
    canUndoStroke: Boolean,
    onUndoStroke: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow {
            Brush.entries.forEach { b ->
                FilterChip(selected = settings.shape == null && settings.brush == b,
                    onClick = { onSettings(settings.copy(brush = b, shape = null)) }, label = { Text(b.label) })
            }
            ShapeKind.entries.forEach { s ->
                FilterChip(selected = settings.shape == s, onClick = { onSettings(settings.copy(shape = s)) }, label = { Text(s.label) })
            }
            if (settings.shape == ShapeKind.RECT || settings.shape == ShapeKind.OVAL) {
                FilterChip(selected = settings.filled, onClick = { onSettings(settings.copy(filled = !settings.filled)) }, label = { Text("Filled") })
            }
        }
        if (settings.brush != Brush.ERASER || settings.shape != null) {
            ColorSwatches(settings.color, onPick = { onSettings(settings.copy(color = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ValueSlider("Width", settings.width, 0.002f..0.06f, onStart = {},
                    onChange = { onSettings(settings.copy(width = it)) }, onReset = { onSettings(settings.copy(width = 0.012f)) },
                    format = { v -> "${(v * 1000).roundToInt()}" })
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUndoStroke, enabled = canUndoStroke) { Text("Undo last") }
            TextButton(onClick = onClear, enabled = canUndoStroke) { Text("Clear drawing") }
        }
    }
}

// ----------------------------------------------------------------- stickers

@Composable
fun StickerPanel(selected: Overlay.Sticker?, onAdd: (String) -> Unit, onDelete: (Overlay.Sticker) -> Unit) {
    Column {
        if (selected != null) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Drag to move · pinch to resize and rotate", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { onDelete(selected) }) { Icon(Icons.Outlined.Delete, "Delete sticker") }
            }
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(STICKERS.chunked(2)) { pair ->
                Column {
                    pair.forEach { g ->
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Add sticker") { onAdd(g) },
                            contentAlignment = Alignment.Center) { Text(g, fontSize = 30.sp) }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- frame

@Composable
fun FramePanel(state: EditState, onStart: () -> Unit, onPreview: ((EditState) -> EditState) -> Unit, onCommit: ((EditState) -> EditState) -> Unit) {
    val f = state.frame
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChipRow {
            FilterChip(selected = f == null, onClick = { onCommit { it.copy(frame = null) } }, label = { Text("None") })
            FrameStyle.entries.forEach { s ->
                FilterChip(selected = f?.style == s, onClick = {
                    onCommit { it.copy(frame = (it.frame ?: Frame(s)).copy(style = s, thickness = if (s == FrameStyle.SQUARE_FIT) 0.02f else (it.frame?.thickness ?: 0.05f))) }
                }, label = { Text(s.label) })
            }
        }
        if (f != null) {
            ColorSwatches(f.color, onPick = { c -> onCommit { it.copy(frame = it.frame?.copy(color = c)) } })
            ValueSlider("Thickness", f.thickness, 0f..0.2f, onStart,
                onChange = { v -> onPreview { it.copy(frame = it.frame?.copy(thickness = v)) } },
                onReset = { onCommit { it.copy(frame = it.frame?.copy(thickness = 0.05f)) } })
            if (f.style != FrameStyle.POLAROID) {
                ValueSlider("Corner radius", f.cornerRadius, 0f..0.2f, onStart,
                    onChange = { v -> onPreview { it.copy(frame = it.frame?.copy(cornerRadius = v)) } },
                    onReset = { onCommit { it.copy(frame = it.frame?.copy(cornerRadius = 0f)) } })
            }
        }
    }
}
