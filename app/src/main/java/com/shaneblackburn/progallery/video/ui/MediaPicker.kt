package com.shaneblackburn.progallery.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.shaneblackburn.progallery.core.format.Formatters
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.ui.components.MediaThumb

private enum class PickFilter(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos") }

/**
 * Full-screen picker over the gallery for building videos. Items are numbered in the
 * order they're tapped, which is the order they're added to the timeline.
 *
 * Uses the gallery's own MediaStore items (not the system photo picker), so saved
 * projects keep permanent access to their media.
 */
@Composable
fun MediaPickerDialog(
    items: List<MediaItem>,
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (List<MediaItem>) -> Unit,
) {
    val picked = remember { mutableStateListOf<MediaItem>() }
    var filter by remember { mutableStateOf(PickFilter.ALL) }
    val shown = remember(items, filter) {
        items.filter { (filter == PickFilter.ALL || (filter == PickFilter.PHOTOS) == it.isImage) && !it.isGif && !it.isRaw }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Button(onClick = { onConfirm(picked.toList()) }, enabled = picked.isNotEmpty()) {
                        Text(if (picked.isEmpty()) confirmLabel else "$confirmLabel (${picked.size})")
                    }
                }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    PickFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) },
                            modifier = Modifier.padding(end = 8.dp))
                    }
                }
                if (shown.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing to show") }
                } else {
                    LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize().padding(2.dp)) {
                        items(shown, key = { it.id }) { item ->
                            val order = picked.indexOfFirst { it.id == item.id }
                            PickCell(item, order) {
                                if (order >= 0) picked.removeAt(order) else picked.add(item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickCell(item: MediaItem, order: Int, onToggle: () -> Unit) {
    val selected = order >= 0
    Box(
        Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .semantics { contentDescription = "Pick ${item.name}" }
            .clickable(onClick = onToggle)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) {
        AsyncImage(MediaThumb(item), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.isVideo) {
            Text(Formatters.duration(item.durationMs), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 4.dp))
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f), CircleShape)
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Text("${order + 1}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
        }
    }
}
