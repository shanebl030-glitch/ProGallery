package com.shaneblackburn.progallery.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.ui.GalleryViewModel
import com.shaneblackburn.progallery.ui.MediaSource
import com.shaneblackburn.progallery.ui.auth.DeviceAuth
import com.shaneblackburn.progallery.ui.components.ConfirmDialog
import com.shaneblackburn.progallery.ui.components.MediaThumb
import com.shaneblackburn.progallery.ui.components.MessageView
import java.util.concurrent.TimeUnit

/** Recently deleted: restore or permanently delete. Android purges items after ~30 days. */
@Composable
fun TrashScreen(vm: GalleryViewModel, onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val items by vm.trash.collectAsState()
    val entriesFlow = remember { vm.entriesFor(MediaSource.Trash) }
    val entries by entriesFlow.collectAsState(initial = emptyList())
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<MediaItem>?>(null) }
    val soonest = items.minOfOrNull { it.trashExpiresAt }?.takeIf { it > 0 }
    val subtitle = when {
        items.isEmpty() -> null
        soonest != null -> {
            val days = TimeUnit.MILLISECONDS.toDays(soonest - System.currentTimeMillis()).coerceAtLeast(0)
            "${items.size} item${if (items.size == 1) "" else "s"} · next removed in $days day${if (days == 1L) "" else "s"}"
        }
        else -> "${items.size} items"
    }

    MediaListScreen(
        vm = vm,
        title = "Recently deleted",
        subtitle = subtitle,
        entries = entries,
        items = items,
        showBack = true,
        onBack = onBack,
        onOpen = onOpen,
        emptyTitle = "Nothing here",
        emptyBody = "Items you delete stay here for 30 days before Android removes them for good.",
        extraActions = {
            if (items.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty") }
        },
        selectionActions = { selected ->
            BottomAppBar(windowInsets = WindowInsets(0), modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { vm.restore(selected) }) {
                        Icon(Icons.Outlined.RestoreFromTrash, null); Text("  Restore")
                    }
                    TextButton(onClick = { confirmDelete = selected }) {
                        Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                        Text("  Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    )

    if (confirmEmpty) {
        ConfirmDialog(
            "Empty Recently deleted?", "All ${items.size} items will be permanently deleted. This can't be undone.",
            "Delete all", onConfirm = { vm.deleteForever(items) }, onDismiss = { confirmEmpty = false }, destructive = true,
        )
    }
    confirmDelete?.let { sel ->
        ConfirmDialog(
            "Delete permanently?", "${sel.size} item${if (sel.size == 1) "" else "s"} will be deleted. This can't be undone.",
            "Delete", onConfirm = { vm.deleteForever(sel) }, onDismiss = { confirmDelete = null }, destructive = true,
        )
    }
}

/**
 * Manage which albums are hidden. Protected by device authentication when enabled,
 * because the list itself reveals what's hidden.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenAlbumsScreen(vm: GalleryViewModel, onBack: () -> Unit, onOpenAlbum: (Long) -> Unit) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val unlocked by vm.hiddenUnlocked.collectAsState()
    val albums by vm.allAlbums.collectAsState()
    val needsAuth = settings.requireAuthForHidden && !unlocked

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hidden albums") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (unlocked) TextButton(onClick = vm::lockHidden) { Text("Lock") }
                },
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            if (needsAuth) {
                MessageView(
                    Icons.Outlined.Lock,
                    "Hidden albums are locked",
                    "Unlock with your fingerprint, face or screen lock to see and manage hidden albums.",
                    primaryAction = "Unlock" to {
                        DeviceAuth.authenticate(context, "Unlock hidden albums", "Confirm it's you") { r ->
                            when (r) {
                                DeviceAuth.Result.Success -> vm.unlockHidden()
                                DeviceAuth.Result.Cancelled -> Unit
                                is DeviceAuth.Result.Failed -> Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                                is DeviceAuth.Result.Unavailable -> Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text("Show hidden albums in gallery") },
                            supportingContent = { Text("While unlocked, hidden albums also appear in Photos and Albums") },
                            trailingContent = { Switch(settings.showHidden, onCheckedChange = vm::setShowHidden) },
                        )
                    }
                    item {
                        Text(
                            "Tap the switch to hide or unhide an album. Hiding only affects ProGallery — other apps can still see these files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(albums, key = { it.bucketId }) { album ->
                        ListItem(
                            headlineContent = { Text(album.name) },
                            supportingContent = { Text("${album.count} items · ${album.relativePath}") },
                            leadingContent = {
                                AsyncImage(
                                    model = album.cover?.let { MediaThumb(it) },
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)),
                                )
                            },
                            trailingContent = {
                                Switch(album.isHidden, onCheckedChange = { vm.setAlbumsHidden(listOf(album.bucketId), it) })
                            },
                            modifier = Modifier.clickable { onOpenAlbum(album.bucketId) },
                        )
                    }
                }
            }
        }
    }
}
