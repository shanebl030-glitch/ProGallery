package com.shaneblackburn.progallery.video.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlin.math.roundToInt

/** What the user has selected on the timeline. */
sealed interface TimelineSelection {
    data class ClipSel(val index: Int) : TimelineSelection
    data class TransitionSel(val afterIndex: Int) : TimelineSelection
    data class TextSel(val id: Long) : TimelineSelection
    data object MusicSel : TimelineSelection
}

/**
 * Scrollable timeline. The playhead is fixed in the centre; scrolling moves time.
 * [pxPerMs] sets the zoom level.
 */
@Composable
fun VideoTimeline(
    project: VideoProject,
    thumbs: Map<String, List<ImageBitmap>>,
    scroll: ScrollState,
    pxPerMs: Float,
    selection: TimelineSelection?,
    onSelect: (TimelineSelection?) -> Unit,
    onTrimStart: () -> Unit,
    onTrim: (index: Int, startMs: Long, endMs: Long) -> Unit,
    /** Long-press-and-drag reorder: move clip [from] to position [to]. */
    onMove: (from: Int, to: Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val currentProject by rememberUpdatedState(project)
    val currentPxPerMs by rememberUpdatedState(pxPerMs)
    // Clip being dragged and how far (px) it has moved.
    var dragIndex by remember { mutableStateOf(-1) }
    var dragDx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val half: Dp = maxWidth / 2
        val totalPx = project.durationMs * pxPerMs
        Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            // Clips row: strips are laid end to end (width == time) and transition buttons
            // float over the boundaries so they don't shift the time scale.
            Box(Modifier.height(64.dp).width(half * 2 + with(density) { totalPx.toDp() })) {
                Row(Modifier.fillMaxHeight()) {
                    Spacer(Modifier.width(half))
                    project.clips.forEachIndexed { i, clip ->
                        val wPx = clip.timelineDurationMs * pxPerMs
                        val selected = selection == TimelineSelection.ClipSel(i)
                        val dragging = dragIndex == i
                        ClipStrip(
                            clip = clip,
                            frames = thumbs[clip.uri].orEmpty(),
                            widthDp = with(density) { wPx.toDp() },
                            selected = selected,
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer {
                                    if (dragging) { translationX = dragDx; scaleY = 0.9f; alpha = 0.85f }
                                }
                                .pointerInput(i, project.clips.size) {
                                    if (project.clips.size < 2) return@pointerInput
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { dragIndex = i; dragDx = 0f },
                                        onDrag = { change, d -> change.consume(); dragDx += d.x },
                                        onDragEnd = {
                                            val to = currentProject.dropIndex(i, dragDx / currentPxPerMs)
                                            dragIndex = -1; dragDx = 0f
                                            if (to != i) onMove(i, to)
                                        },
                                        onDragCancel = { dragIndex = -1; dragDx = 0f },
                                    )
                                },
                            onClick = { onSelect(if (selected) null else TimelineSelection.ClipSel(i)) },
                            onTrimStart = onTrimStart,
                            onDragStartHandle = { dxPx ->
                                val c = currentProject.clips[i]
                                val deltaMs = (dxPx / pxPerMs * c.speed).toLong()
                                onTrim(i, c.trimStartMs + deltaMs, c.trimEndMs)
                            },
                            onDragEndHandle = { dxPx ->
                                val c = currentProject.clips[i]
                                val deltaMs = (dxPx / pxPerMs * c.speed).toLong()
                                onTrim(i, c.trimStartMs, c.trimEndMs + deltaMs)
                            },
                        )
                    }
                }
                val starts = project.clipStarts()
                for (i in 0 until project.clips.size - 1) {
                    val x = half + with(density) { (starts[i + 1] * pxPerMs).toDp() } - 11.dp
                    Box(Modifier.offset(x = x).fillMaxHeight()) {
                        TransitionButton(
                            type = project.transitionAfter(i).type,
                            selected = selection == TimelineSelection.TransitionSel(i),
                            onClick = { onSelect(TimelineSelection.TransitionSel(i)) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // Text track
            Box(Modifier.height(26.dp).width(half * 2 + with(density) { totalPx.toDp() })) {
                project.texts.forEach { t ->
                    val x = with(density) { (t.startMs * pxPerMs).toDp() } + half
                    val w = with(density) { ((t.endMs - t.startMs) * pxPerMs).toDp() }.coerceAtLeast(24.dp)
                    val sel = selection == TimelineSelection.TextSel(t.id)
                    Row(
                        Modifier.offset(x = x).width(w).fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiaryContainer)
                            .clickable { onSelect(if (sel) null else TimelineSelection.TextSel(t.id)) }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.TextFields, null, Modifier.size(14.dp))
                        Text(t.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            // Music track
            project.music?.let { m ->
                val sel = selection == TimelineSelection.MusicSel
                Row(Modifier.height(26.dp)) {
                    Spacer(Modifier.width(half))
                    Row(
                        Modifier.width(with(density) { totalPx.toDp() }.coerceAtLeast(40.dp)).fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.secondaryContainer)
                            .clickable { onSelect(if (sel) null else TimelineSelection.MusicSel) }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.MusicNote, null, Modifier.size(14.dp))
                        Text(m.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
                    }
                    Spacer(Modifier.width(half))
                }
            }
        }
        // Fixed centre playhead
        Box(
            Modifier.align(Alignment.TopCenter).width(2.dp).height(if (project.music != null) 132.dp else 104.dp)
                .background(Color.White),
        )
    }
}

@Composable
private fun ClipStrip(
    clip: Clip,
    frames: List<ImageBitmap>,
    widthDp: Dp,
    selected: Boolean,
    onClick: () -> Unit,
    onTrimStart: () -> Unit,
    onDragStartHandle: (Float) -> Unit,
    onDragEndHandle: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleColor = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .semantics { contentDescription = "Clip ${clip.name}" }
            .width(widthDp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (selected) Modifier.border(3.dp, handleColor, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        // Frames from the trimmed range only, tiled across the strip.
        val inRange = if (frames.isEmpty()) emptyList() else frames.filterIndexed { i, _ ->
            val t = clip.sourceDurationMs * i / frames.size
            t >= clip.trimStartMs - clip.sourceDurationMs / frames.size && t <= clip.trimEndMs
        }.ifEmpty { frames.take(1) }
        Row(Modifier.fillMaxHeight()) {
            val tiles = ((widthDp.value / 56f).roundToInt()).coerceAtLeast(1)
            repeat(tiles) { k ->
                val f = inRange.getOrNull((k * inRange.size / tiles).coerceAtMost(inRange.lastIndex.coerceAtLeast(0)))
                if (f != null) Image(f, null, contentScale = ContentScale.Crop, modifier = Modifier.width(widthDp / tiles).fillMaxHeight())
            }
        }
        if (clip.speed != 1f || clip.muted) {
            Text(
                buildString { if (clip.speed != 1f) append("${clip.speed}×"); if (clip.muted) append(if (isEmpty()) "Muted" else " · Muted") },
                color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp),
            )
        }
        if (clip.isImage) {
            Icon(Icons.Outlined.Photo, null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(3.dp).size(14.dp).background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(3.dp)))
        }
        // Photos have no trim range: their length is set with "Photo duration".
        if (selected && !clip.isImage) {
            TrimHandle(Modifier.align(Alignment.CenterStart), handleColor, onTrimStart, onDragStartHandle)
            TrimHandle(Modifier.align(Alignment.CenterEnd), handleColor, onTrimStart, onDragEndHandle)
        }
    }
}

@Composable
private fun TrimHandle(modifier: Modifier, color: Color, onStart: () -> Unit, onDrag: (Float) -> Unit) {
    Box(
        modifier
            .width(18.dp)
            .fillMaxHeight()
            .background(color)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragStart = { onStart() }) { change, dx ->
                    change.consume(); onDrag(dx)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(20.dp).background(Color.White, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun TransitionButton(type: TransitionType, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.width(22.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (selected || type != TransitionType.NONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClickLabel = "Transition", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when (type) {
                    TransitionType.NONE -> "|"
                    TransitionType.FADE_BLACK -> "◐"
                    TransitionType.FADE_WHITE -> "○"
                    TransitionType.BLUR -> "❋"
                    TransitionType.ZOOM -> "⤢"
                },
                fontSize = 11.sp,
                color = if (selected || type != TransitionType.NONE) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

