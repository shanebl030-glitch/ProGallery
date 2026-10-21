package com.shaneblackburn.progallery.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.RawOn
import androidx.compose.material.icons.outlined.ScreenshotMonitor
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.query.SmartCollection
import com.shaneblackburn.progallery.ui.GalleryViewModel

fun SmartCollection.icon(): ImageVector = when (this) {
    SmartCollection.FAVORITES -> Icons.Outlined.FavoriteBorder
    SmartCollection.VIDEOS -> Icons.Outlined.Videocam
    SmartCollection.RECENTLY_ADDED -> Icons.Outlined.NewReleases
    SmartCollection.RECENTLY_VIEWED -> Icons.Outlined.History
    SmartCollection.CAMERA -> Icons.Outlined.CameraAlt
    SmartCollection.SCREENSHOTS -> Icons.Outlined.ScreenshotMonitor
    SmartCollection.SCREEN_RECORDINGS -> Icons.Outlined.FiberManualRecord
    SmartCollection.DOWNLOADS -> Icons.Outlined.Download
    SmartCollection.GIFS -> Icons.Outlined.Animation
    SmartCollection.RAW -> Icons.Outlined.RawOn
    SmartCollection.MOTION -> Icons.Outlined.MotionPhotosOn
    SmartCollection.LARGE -> Icons.Outlined.PhotoSizeSelectLarge
}

/** Collections that are always listed even when empty (others hide at zero). */
private val ALWAYS_SHOWN = setOf(
    SmartCollection.FAVORITES, SmartCollection.VIDEOS, SmartCollection.RECENTLY_ADDED, SmartCollection.RECENTLY_VIEWED,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: GalleryViewModel,
    onOpen: (SmartCollection) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenHidden: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProjects: () -> Unit,
    bottomPadding: PaddingValues,
) {
    val projectStore = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.shaneblackburn.progallery.ProGalleryApp).projects
    val projects by projectStore.projects.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { projectStore.refresh() }
    val countsFlow = remember { vm.counts() }
    val counts by countsFlow.collectAsState(initial = emptyMap())
    val trash by vm.trash.collectAsState()
    val settings by vm.settings.collectAsState()
    val visible = SmartCollection.entries.filter { it in ALWAYS_SHOWN || (counts[it] ?: 0) > 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = { IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Settings") } },
            )
        },
    ) { inner ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 4.dp,
                bottom = bottomPadding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(visible, key = { it.name }) { c ->
                CollectionTile(c.icon(), c.title, counts[c], onClick = { onOpen(c) })
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Create", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            }
            item(key = "projects") {
                CollectionTile(Icons.Outlined.Movie, "Video projects", projects.size, onClick = onOpenProjects)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Manage", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            }
            if (vm.supportsTrash) {
                item(key = "trash") {
                    CollectionTile(Icons.Outlined.Delete, "Recently deleted", trash.size, onClick = onOpenTrash)
                }
            }
            item(key = "hidden") {
                CollectionTile(Icons.Outlined.Lock, "Hidden albums", settings.hiddenBuckets.size, onClick = onOpenHidden)
            }
        }
    }
}

@Composable
private fun CollectionTile(icon: ImageVector, title: String, count: Int?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().height(96.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
                Box(Modifier.weight(1f))
                if (count != null) {
                    Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

