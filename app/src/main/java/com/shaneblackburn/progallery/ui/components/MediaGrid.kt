package com.shaneblackburn.progallery.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.query.GridEntry
import com.shaneblackburn.progallery.ui.theme.SectionHeaderStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sectioned media grid with:
 *  - pinch to change the column count,
 *  - long-press then drag to select a range (auto-scrolls near the edges),
 *  - tap to open / toggle selection,
 *  - per-section "select all" in selection mode,
 *  - fast-scroll thumb with a date bubble.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    entries: List<GridEntry>,
    columns: Int,
    selection: Set<Long>,
    onOpen: (MediaItem, Int) -> Unit,
    onToggle: (Long) -> Unit,
    onSetSelection: (Set<Long>) -> Unit,
    onColumnsChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    minColumns: Int = 2,
    maxColumns: Int = 8,
    header: (@Composable () -> Unit)? = null,
) {
    val selectionMode = selection.isNotEmpty()
    val haptics = LocalHapticFeedback.current
    val currentEntries by rememberUpdatedState(entries)
    val currentSelection by rememberUpdatedState(selection)
    val currentColumns by rememberUpdatedState(columns)
    val autoScrollSpeed = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(autoScrollSpeed.floatValue) {
        if (autoScrollSpeed.floatValue != 0f) {
            while (isActive) {
                state.scrollBy(autoScrollSpeed.floatValue)
                delay(10)
            }
        }
    }

    val headerOffset = if (header != null) 1 else 0

    BoxWithConstraints(modifier) {
        val heightPx = constraints.maxHeight.toFloat()
        val edgePx = with(LocalDensity.current) { 64.dp.toPx() }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .pinchToChangeColumns(currentColumns, minColumns, maxColumns, onColumnsChange)
                .pointerInput(Unit) {
                    var anchorIndex: Int? = null
                    var initialSelection: Set<Long> = emptySet()
                    var anchorWasSelected = false
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val idx = state.entryIndexAt(offset, headerOffset) ?: return@detectDragGesturesAfterLongPress
                            val entry = currentEntries.getOrNull(idx) as? GridEntry.Item ?: return@detectDragGesturesAfterLongPress
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            anchorIndex = idx
                            initialSelection = currentSelection
                            anchorWasSelected = entry.item.id in currentSelection
                            onSetSelection(
                                if (anchorWasSelected) currentSelection - entry.item.id
                                else currentSelection + entry.item.id,
                            )
                        },
                        onDragCancel = { anchorIndex = null; autoScrollSpeed.floatValue = 0f },
                        onDragEnd = { anchorIndex = null; autoScrollSpeed.floatValue = 0f },
                        onDrag = { change, _ ->
                            val anchor = anchorIndex ?: return@detectDragGesturesAfterLongPress
                            val y = change.position.y
                            autoScrollSpeed.floatValue = when {
                                y > heightPx - edgePx -> (y - (heightPx - edgePx)) / 4f
                                y < edgePx -> -(edgePx - y) / 4f
                                else -> 0f
                            }
                            val idx = state.entryIndexAt(change.position, headerOffset) ?: return@detectDragGesturesAfterLongPress
                            val range = if (idx >= anchor) anchor..idx else idx..anchor
                            val ids = range.mapNotNull { (currentEntries.getOrNull(it) as? GridEntry.Item)?.item?.id }
                            onSetSelection(
                                if (anchorWasSelected) initialSelection - ids.toSet() else initialSelection + ids,
                            )
                        },
                    )
                },
        ) {
            if (header != null) {
                item(key = "__header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
            }
            items(
                count = entries.size,
                key = { entries[it].key },
                span = { i -> if (entries[i] is GridEntry.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { i -> if (entries[i] is GridEntry.Header) "section" else "media" },
            ) { i ->
                when (val e = entries[i]) {
                    is GridEntry.Header -> SectionHeader(
                        label = e.label,
                        selectionMode = selectionMode,
                        allSelected = selectionMode && e.itemIds.all { it in selection },
                        onToggleAll = {
                            if (e.itemIds.all { it in selection }) onSetSelection(selection - e.itemIds.toSet())
                            else onSetSelection(selection + e.itemIds)
                        },
                        modifier = Modifier.animateItem(),
                    )
                    is GridEntry.Item -> MediaCell(
                        item = e.item,
                        selected = e.item.id in selection,
                        selectionMode = selectionMode,
                        modifier = Modifier
                            .semantics { contentDescription = e.item.name }
                            .clickable {
                                if (selectionMode) onToggle(e.item.id) else onOpen(e.item, e.index)
                            },
                    )
                }
            }
        }

        FastScroller(
            state = state,
            entries = entries,
            headerOffset = headerOffset,
            modifier = Modifier.align(Alignment.TopEnd).padding(contentPadding),
        )
    }
}

@Composable
private fun SectionHeader(
    label: String,
    selectionMode: Boolean,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = SectionHeaderStyle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selectionMode) {
            IconButton(onClick = onToggleAll) {
                Icon(
                    if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (allSelected) "Deselect $label" else "Select all in $label",
                    tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Finds the grid entry under [offset] (grid-local coordinates), adjusted for a header row. */
private fun LazyGridState.entryIndexAt(offset: Offset, headerOffset: Int): Int? {
    val hit = layoutInfo.visibleItemsInfo.firstOrNull { info ->
        val x = offset.x.roundToInt()
        val y = offset.y.roundToInt()
        x >= info.offset.x && x < info.offset.x + info.size.width &&
            y >= info.offset.y && y < info.offset.y + info.size.height
    } ?: return null
    val idx = hit.index - headerOffset
    return if (idx >= 0) idx else null
}

/**
 * Two-finger pinch changes the column count in steps. Single-finger input passes
 * through untouched, so scrolling and drag-select keep working.
 */
fun Modifier.pinchToChangeColumns(
    columns: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
): Modifier = pointerInput(columns, min, max) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var startSpan = 0f
        var changed = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val span = (a - b).getDistance()
                if (startSpan == 0f) startSpan = span
                val ratio = span / startSpan
                if (!changed && (ratio > 1.35f || ratio < 0.74f)) {
                    val next = if (ratio > 1f) columns - 1 else columns + 1
                    if (next in min..max) onChange(next)
                    changed = true
                }
                // Consume so the grid doesn't scroll while pinching.
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

@Composable
private fun FastScroller(
    state: LazyGridState,
    entries: List<GridEntry>,
    headerOffset: Int,
    modifier: Modifier = Modifier,
) {
    val total = entries.size + headerOffset
    if (total < 60) return
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var trackHeight by remember { mutableFloatStateOf(1f) }
    val scrolling by remember { derivedStateOf { state.isScrollInProgress } }
    val progress by remember(total) {
        derivedStateOf { (state.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1)).coerceIn(0f, 1f) }
    }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) visible = true else { delay(1500); visible = false }
    }
    val thumbHeight = 48.dp
    val density = LocalDensity.current
    val thumbPx = with(density) { thumbHeight.toPx() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        trackHeight = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                val y = (progress * trackHeight).roundToInt()
                if (dragging) {
                    val label by remember(entries, headerOffset) {
                        derivedStateOf { labelAt(entries, (state.firstVisibleItemIndex - headerOffset).coerceAtLeast(0)) }
                    }
                    label?.let { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset { IntOffset(-with(density) { 44.dp.roundToPx() }, y) }
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, y) }
                        .width(36.dp)
                        .height(thumbHeight)
                        .pointerInput(total) {
                            detectVerticalDragGestures(
                                onDragStart = { dragging = true },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                            ) { change, dy ->
                                change.consume()
                                val newProgress = (progress + dy / trackHeight).coerceIn(0f, 1f)
                                val target = (newProgress * (total - 1)).roundToInt()
                                if (abs(target - state.firstVisibleItemIndex) > 0) {
                                    scope.launch { state.scrollToItem(target) }
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .padding(end = 4.dp)
                            .width(if (dragging) 8.dp else 6.dp)
                            .height(thumbHeight)
                            .background(
                                if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                RoundedCornerShape(4.dp),
                            ),
                    )
                }
            }
        }
    }
}

/** Nearest section header label at or before [index]. */
private fun labelAt(entries: List<GridEntry>, index: Int): String? {
    var i = index.coerceAtMost(entries.lastIndex)
    while (i >= 0) {
        val e = entries[i]
        if (e is GridEntry.Header) return e.label
        i--
    }
    return null
}

