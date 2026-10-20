package com.shaneblackburn.progallery.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.ui.GalleryViewModel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Clear selection") } },
        actions = { IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, "Select all") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/**
 * Bottom action bar for multi-select: share, favourite, move, copy, delete.
 * Owns its confirmation and folder-picker dialogs.
 */
@Composable
fun SelectionActionBar(
    vm: GalleryViewModel,
    selected: List<MediaItem>,
) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val albums by vm.allAlbums.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) } // "move" | "copy"
    val allFav = selected.isNotEmpty() && selected.all { it.isFavorite }

    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        windowInsets = WindowInsets(0),
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.shareIntent(selected)?.let { runCatching { context.startActivity(it) } } }) {
                Icon(Icons.Outlined.Share, "Share")
            }
            IconButton(onClick = { vm.setFavorite(selected, !allFav) }) {
                Icon(if (allFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    if (allFav) "Remove from favourites" else "Add to favourites")
            }
            IconButton(onClick = { picker = "move" }) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "Move to album") }
            IconButton(onClick = { picker = "copy" }) { Icon(Icons.Outlined.ContentCopy, "Copy to album") }
            IconButton(onClick = { if (settings.confirmTrash) confirmDelete = true else vm.trash(selected) }) {
                Icon(Icons.Outlined.Delete, "Delete")
            }
        }
    }

    if (confirmDelete) {
        DeleteConfirm(vm, selected) { confirmDelete = false }
    }
    picker?.let { mode ->
        FolderPickerDialog(
            title = if (mode == "move") "Move to" else "Copy to",
            albums = albums,
            videoOnly = selected.all { it.isVideo },
            onPick = { path -> if (mode == "move") vm.move(selected, path) else vm.copy(selected, path) },
            onDismiss = { picker = null },
        )
    }
}

@Composable
fun DeleteConfirm(vm: GalleryViewModel, items: List<MediaItem>, onDismiss: () -> Unit) {
    val n = items.size
    val what = if (n == 1) "this item" else "$n items"
    ConfirmDialog(
        title = if (vm.supportsTrash) "Move to Recently deleted?" else "Delete permanently?",
        text = if (vm.supportsTrash) "You can restore $what from Recently deleted for 30 days."
        else "Android 10 has no system trash, so $what will be deleted permanently.",
        confirmLabel = if (vm.supportsTrash) "Move" else "Delete",
        destructive = true,
        onConfirm = { vm.trash(items) },
        onDismiss = onDismiss,
    )
}
