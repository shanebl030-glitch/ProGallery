package com.shaneblackburn.progallery.ui.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Rotate90DegreesCw
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.request.ImageRequest
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.ui.GalleryViewModel
import com.shaneblackburn.progallery.ui.MediaSource
import com.shaneblackburn.progallery.ui.components.ConfirmDialog
import com.shaneblackburn.progallery.ui.components.DeleteConfirm
import com.shaneblackburn.progallery.ui.components.FolderPickerDialog
import com.shaneblackburn.progallery.ui.components.NameDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen, swipeable viewer for photos and videos.
 *
 * The list is re-derived live from [source], so deleting, moving or renaming updates
 * the pager in place; the viewer follows the current item by id.
 */
@Composable
fun ViewerScreen(
    vm: GalleryViewModel,
    source: MediaSource,
    startId: Long,
    onBack: () -> Unit,
    startSlideshow: Boolean = false,
    onEdit: (MediaItem) -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val itemsFlow = remember(source.key) { vm.itemsFor(source) }
    val items by itemsFlow.collectAsState(initial = null)
    val settings by vm.settings.collectAsState()
    val albums by vm.allAlbums.collectAsState()
    val list = items

    if (list == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    var currentId by rememberSaveable { mutableLongStateOf(startId) }
    val initialPage = remember { list.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = initialPage) { list.size }
    val scope = rememberCoroutineScope()
    var chrome by rememberSaveable { mutableStateOf(true) }
    var slideshow by rememberSaveable { mutableStateOf(startSlideshow) }
    var showInfo by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val rotations = remember { mutableStateMapOf<Long, Int>() }
    val player = remember { buildPlayer(context) }
    var landscapeLock by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Track the current item by id so list changes (deletes) don't jump to a random item.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            list.getOrNull(page)?.let { currentId = it.id; vm.markViewed(it.id) }
        }
    }
    LaunchedEffect(list) {
        val idx = list.indexOfFirst { it.id == currentId }
        if (idx >= 0 && idx != pager.currentPage) pager.scrollToPage(idx)
        else if (idx < 0) list.getOrNull(pager.currentPage.coerceAtMost(list.lastIndex))?.let { currentId = it.id }
    }

    val current = list.getOrNull(pager.currentPage.coerceIn(0, list.lastIndex)) ?: list.first()
    val inTrash = source == MediaSource.Trash

    // Immersive mode follows the chrome; keep the screen on during slideshow/video.
    DisposableEffect(chrome, slideshow) {
        val window = activity?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (chrome && !slideshow) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
            c.isAppearanceLightStatusBars = false
        }
        onDispose {
            if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(slideshow, current.isVideo) {
        view.keepScreenOn = slideshow || current.isVideo
        onDispose { view.keepScreenOn = false }
    }

    // Slideshow: advance photos on a timer; videos advance when they finish.
    LaunchedEffect(slideshow, pager.settledPage) {
        if (!slideshow) return@LaunchedEffect
        chrome = false
        if (!current.isVideo) {
            delay(settings.slideshowSeconds * 1000L)
            val next = pager.settledPage + 1
            if (next < list.size) pager.animateScrollToPage(next) else slideshow = false
        }
    }

    BackHandler(enabled = slideshow) { slideshow = false; chrome = true }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { list.getOrNull(it)?.id ?: it },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = list.getOrNull(page) ?: return@HorizontalPager
            val isCurrent = page == pager.settledPage
            if (item.isVideo) {
                VideoPage(
                    player = player,
                    uri = item.uri,
                    active = isCurrent,
                    autoPlay = settings.autoPlayVideos || slideshow,
                    loop = settings.loopVideos && !slideshow,
                    controlsVisible = chrome,
                    onToggleControls = { if (slideshow) slideshow = false else chrome = !chrome },
                    onRotate = {
                        landscapeLock = (landscapeLock + 1) % 2
                        activity?.requestedOrientation = if (landscapeLock == 1)
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    },
                    onEnded = {
                        if (slideshow) scope.launch {
                            val next = pager.settledPage + 1
                            if (next < list.size) pager.animateScrollToPage(next) else slideshow = false
                        }
                    },
                )
            } else {
                PhotoPage(
                    item = item,
                    rotation = rotations[item.id] ?: 0,
                    onTap = { if (slideshow) { slideshow = false; chrome = true } else chrome = !chrome },
                )
            }
        }

        // Top bar
        AnimatedVisibility(chrome && !slideshow, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    val date = Date(current.effectiveDate)
                    Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(date), color = Color.White,
                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(date) + "  ·  ${pager.currentPage + 1} of ${list.size}",
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!current.isVideo && !inTrash) {
                    IconButton(onClick = { rotations[current.id] = ((rotations[current.id] ?: 0) + 90) % 360 }) {
                        Icon(Icons.Outlined.Rotate90DegreesCw, "Rotate view", tint = Color.White)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!inTrash) {
                            MenuItem("Slideshow", Icons.Outlined.Slideshow) { menu = false; slideshow = true }
                            MenuItem("Rename", Icons.Outlined.DriveFileRenameOutline) { menu = false; dialog = "rename" }
                            MenuItem("Move to album", Icons.AutoMirrored.Outlined.DriveFileMove) { menu = false; dialog = "move" }
                            MenuItem("Copy to album", Icons.Outlined.ContentCopy) { menu = false; dialog = "copy" }
                            if (current.isImage) MenuItem("Set as…", Icons.Outlined.Wallpaper) {
                                menu = false; runCatching { context.startActivity(vm.setAsIntent(current)) }
                            }
                        }
                        MenuItem("Open with…", Icons.AutoMirrored.Outlined.OpenInNew) {
                            menu = false; runCatching { context.startActivity(vm.openWithIntent(current)) }
                        }
                    }
                }
            }
        }

        // Bottom bar
        AnimatedVisibility(chrome && !slideshow, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (inTrash) {
                    IconButton(onClick = { vm.restore(listOf(current)) }) {
                        Icon(Icons.Outlined.RestoreFromTrash, "Restore", tint = Color.White)
                    }
                    IconButton(onClick = { dialog = "deleteForever" }) {
                        Icon(Icons.Outlined.DeleteForever, "Delete permanently", tint = Color.White)
                    }
                } else {
                    IconButton(onClick = { vm.shareIntent(listOf(current))?.let { runCatching { context.startActivity(it) } } }) {
                        Icon(Icons.Outlined.Share, "Share", tint = Color.White)
                    }
                    IconButton(onClick = { vm.toggleFavorite(current) }) {
                        Icon(
                            if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            if (current.isFavorite) "Remove from favourites" else "Add to favourites",
                            tint = if (current.isFavorite) Color(0xFFFF6B81) else Color.White,
                        )
                    }
                    if ((current.isImage && !current.isGif) || current.isVideo) {
                        IconButton(onClick = { onEdit(current) }) { Icon(Icons.Outlined.Edit, "Edit", tint = Color.White) }
                    }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Outlined.Info, "Details", tint = Color.White) }
                    IconButton(onClick = { if (settings.confirmTrash) dialog = "trash" else vm.trash(listOf(current)) }) {
                        Icon(Icons.Outlined.Delete, "Delete", tint = Color.White)
                    }
                }
            }
        }
    }

    if (showInfo) InfoSheet(current, vm::details) { showInfo = false }

    when (dialog) {
        "trash" -> DeleteConfirm(vm, listOf(current)) { dialog = null }
        "deleteForever" -> ConfirmDialog(
            "Delete permanently?", "This can't be undone.", "Delete",
            onConfirm = { vm.deleteForever(listOf(current)) }, onDismiss = { dialog = null }, destructive = true,
        )
        "rename" -> NameDialog("Rename", current.name, "Rename", onConfirm = { vm.rename(current, it) }, onDismiss = { dialog = null })
        "move", "copy" -> FolderPickerDialog(
            title = if (dialog == "move") "Move to" else "Copy to",
            albums = albums,
            videoOnly = current.isVideo,
            onPick = { path -> if (dialog == "move") vm.move(listOf(current), path) else vm.copy(listOf(current), path) },
            onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun MenuItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun PhotoPage(item: MediaItem, rotation: Int, onTap: () -> Unit) {
    val context = LocalContext.current
    val zoomState = rememberZoomableImageState(rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 10f)))
    val request = remember(item.uri, item.dateModified, rotation) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .placeholderMemoryCacheKey("thumb:${item.uri}:${item.dateModified xor item.sizeBytes}")
            .apply { if (rotation != 0) transformations(RotateTransformation(rotation)) }
            .crossfade(false)
            .build()
    }
    ZoomableAsyncImage(
        model = request,
        contentDescription = item.name,
        state = zoomState,
        onClick = { onTap() },
        modifier = Modifier.fillMaxSize(),
    )
}
