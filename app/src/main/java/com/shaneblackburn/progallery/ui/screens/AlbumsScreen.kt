package com.shaneblackburn.progallery.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.shaneblackburn.progallery.core.model.Album
import com.shaneblackburn.progallery.ui.GalleryViewModel
import com.shaneblackburn.progallery.ui.LibraryState
import com.shaneblackburn.progallery.ui.components.AlbumViewOptionsSheet
import com.shaneblackburn.progallery.ui.components.EmptyView
import com.shaneblackburn.progallery.ui.components.LoadingView
import com.shaneblackburn.progallery.ui.components.MediaThumb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    vm: GalleryViewModel,
    onOpenAlbum: (Album) -> Unit,
    bottomPadding: PaddingValues,
) {
    val library by vm.library.collectAsState()
    val albums by vm.albums.collectAsState()
    val settings by vm.settings.collectAsState()
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Album?>(null) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Albums") },
                actions = { IconButton(onClick = { showOptions = true }) { Icon(Icons.Outlined.Tune, "Album options") } },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            when {
                library is LibraryState.Loading -> LoadingView()
                albums.isEmpty() -> EmptyView("No albums yet", "Folders with photos or videos will appear here.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(settings.albumColumns),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp,
                        bottom = bottomPadding.calculateBottomPadding() + 12.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(albums, key = { it.bucketId }) { album ->
                        AlbumCard(album, onClick = { onOpenAlbum(album) }, onLongClick = { menuFor = album },
                            modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }

    if (showOptions) {
        AlbumViewOptionsSheet(
            sort = settings.albumSort,
            columns = settings.albumColumns,
            onSort = vm::setAlbumSort,
            onColumns = vm::setAlbumColumns,
            onDismiss = { showOptions = false },
        )
    }
    menuFor?.let { album -> AlbumMenu(vm, album) { menuFor = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() },
                onLongClickLabel = "Album options",
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            val cover = album.cover
            if (cover != null) {
                AsyncImage(
                    model = remember(cover.uri, cover.dateModified) {
                        ImageRequest.Builder(context).data(MediaThumb(cover)).build()
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Outlined.Folder, null, Modifier.align(Alignment.Center).size(48.dp))
            }
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                if (album.isPinned) Badge { Icon(Icons.Filled.PushPin, "Pinned", tint = Color.White, modifier = Modifier.size(14.dp)) }
                if (album.isHidden) Badge { Icon(Icons.Outlined.VisibilityOff, "Hidden", tint = Color.White, modifier = Modifier.size(14.dp)) }
            }
        }
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp),
        )
        Text(
            buildString {
                append(album.count)
                if (album.videoCount > 0 && album.imageCount > 0) append(" · ${album.videoCount} video${if (album.videoCount == 1) "" else "s"}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun Badge(content: @Composable () -> Unit) {
    Box(
        Modifier
            .padding(start = 4.dp)
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .padding(4.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumMenu(vm: GalleryViewModel, album: Album, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            ListItem(
                headlineContent = { Text(album.name, style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(album.relativePath.ifBlank { "Unknown location" }) },
            )
            ListItem(
                headlineContent = { Text(if (album.isPinned) "Unpin from top" else "Pin to top") },
                leadingContent = { Icon(if (album.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, null) },
                modifier = Modifier.combinedClickableCompat { vm.setAlbumsPinned(listOf(album.bucketId), !album.isPinned); onDismiss() },
            )
            ListItem(
                headlineContent = { Text(if (album.isHidden) "Unhide album" else "Hide album") },
                supportingContent = {
                    Text(if (album.isHidden) "Show it in Photos and Albums again"
                    else "Removes it from Photos and Albums. Reveal hidden albums from Settings.")
                },
                leadingContent = { Icon(if (album.isHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null) },
                modifier = Modifier.combinedClickableCompat { vm.setAlbumsHidden(listOf(album.bucketId), !album.isHidden); onDismiss() },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = combinedClickable(onClick = onClick)
