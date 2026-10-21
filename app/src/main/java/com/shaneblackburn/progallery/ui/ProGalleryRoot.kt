package com.shaneblackburn.progallery.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.query.SmartCollection
import com.shaneblackburn.progallery.editor.ui.EditorScreen
import com.shaneblackburn.progallery.video.ui.ProjectsScreen
import com.shaneblackburn.progallery.video.ui.VideoEditorScreen
import com.shaneblackburn.progallery.video.ui.VideoOpen
import com.shaneblackburn.progallery.ui.components.ErrorView
import com.shaneblackburn.progallery.ui.components.LoadingView
import com.shaneblackburn.progallery.ui.components.MessageView
import com.shaneblackburn.progallery.ui.screens.AboutScreen
import com.shaneblackburn.progallery.ui.screens.AlbumsScreen
import com.shaneblackburn.progallery.ui.screens.GuideScreen
import com.shaneblackburn.progallery.ui.screens.HiddenAlbumsScreen
import com.shaneblackburn.progallery.ui.screens.LibraryScreen
import com.shaneblackburn.progallery.ui.screens.MediaListScreen
import com.shaneblackburn.progallery.ui.screens.SearchScreen
import com.shaneblackburn.progallery.ui.screens.SettingsScreen
import com.shaneblackburn.progallery.ui.screens.TrashScreen
import com.shaneblackburn.progallery.ui.viewer.ViewerScreen
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab("photos", "Photos", Icons.Outlined.Photo, Icons.Filled.Photo),
    Tab("albums", "Albums", Icons.Outlined.PhotoAlbum, Icons.Filled.PhotoAlbum),
    Tab("library", "Library", Icons.Outlined.Collections, Icons.Filled.Collections),
    Tab("search", "Search", Icons.Outlined.Search, Icons.Filled.Search),
)

object Routes {
    fun collection(source: MediaSource) = "collection/${Uri.encode(source.key)}"
    fun viewer(source: MediaSource, id: Long, slideshow: Boolean = false) =
        "viewer/${Uri.encode(source.key)}/$id?slideshow=$slideshow"
}

@Composable
fun ProGalleryRoot(vm: GalleryViewModel) {
    val context = LocalContext.current
    val library by vm.library.collectAsState()
    var access by remember { mutableStateOf(Permissions.access(context)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = Permissions.access(context)
        vm.onPermissionState(access != MediaAccess.NONE)
    }

    // Re-check on every resume: the user may have changed access in system settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val now = Permissions.access(context)
                if (now != access) access = now
                vm.onPermissionState(now != MediaAccess.NONE)
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        if (access == MediaAccess.NONE) permissionLauncher.launch(Permissions.required())
        else vm.onPermissionState(true)
    }

    // System consent dialog for trash/delete/rename/move.
    var pendingConsent by remember { mutableStateOf<UiEvent.Consent?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pendingConsent?.let { vm.onConsentResult(it.request, result.resultCode == Activity.RESULT_OK, it.successMessage) }
        pendingConsent = null
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is UiEvent.Message -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(e.text) }
                is UiEvent.Consent -> {
                    pendingConsent = e
                    runCatching { consentLauncher.launch(IntentSenderRequest.Builder(e.request.intentSender).build()) }
                        .onFailure {
                            pendingConsent = null
                            snackbar.showSnackbar("Couldn't open the system confirmation")
                        }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            access == MediaAccess.NONE -> PermissionView(
                onGrant = { permissionLauncher.launch(Permissions.required()) },
                onSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }
                },
            )
            library is LibraryState.Error -> ErrorView((library as LibraryState.Error).message, onRetry = vm::reload)
            library is LibraryState.Loading -> LoadingView()
            else -> AppNav(vm, partial = access == MediaAccess.PARTIAL, onManageAccess = {
                permissionLauncher.launch(Permissions.required())
            })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }
}

@Composable
private fun PermissionView(onGrant: () -> Unit, onSettings: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        MessageView(
            icon = Icons.Outlined.PhotoLibrary,
            title = "Allow access to your photos",
            body = "ProGallery needs permission to show the photos and videos on this device. " +
                "Nothing ever leaves your phone — ProGallery has no internet access.",
            primaryAction = "Allow access" to onGrant,
            secondaryAction = "Open settings" to onSettings,
        )
    }
}

@Composable
private fun AppNav(vm: GalleryViewModel, partial: Boolean, onManageAccess: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val selection by vm.selection.collectAsState()
    val showBars = route in TABS.map { it.route } && selection.isEmpty()
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    // Selection belongs to one screen; clear it whenever the destination changes.
    LaunchedEffect(route) { vm.clearSelection() }

    val barPadding = if (showBars && !wide) PaddingValues(bottom = 80.dp) else PaddingValues()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = if (wide && showBars) 80.dp else 0.dp)) {
            NavGraph(nav, vm, barPadding, partial, onManageAccess)
        }
        if (wide) {
            AnimatedVisibility(showBars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                NavigationRail(Modifier.fillMaxHeight()) {
                    Box(Modifier.weight(1f))
                    TABS.forEach { tab ->
                        NavigationRailItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(if (route == tab.route) tab.selectedIcon else tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                    Box(Modifier.weight(1f))
                }
            }
        } else {
            AnimatedVisibility(
                showBars,
                enter = slideInVertically { it }, exit = slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(if (route == tab.route) tab.selectedIcon else tab.icon, null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun NavGraph(
    nav: NavHostController,
    vm: GalleryViewModel,
    barPadding: PaddingValues,
    partial: Boolean,
    onManageAccess: () -> Unit,
) {
    val openViewer: (MediaSource, MediaItem) -> Unit = { src, item -> nav.navigate(Routes.viewer(src, item.id)) }

    NavHost(nav, startDestination = "photos") {
        composable("photos") {
            val entries by vm.timelineEntries.collectAsState()
            val items by vm.timeline.collectAsState()
            MediaListScreen(
                vm = vm, title = "Photos", subtitle = null, entries = entries, items = items,
                showBack = false, onBack = {}, onOpen = { openViewer(MediaSource.Timeline, it) },
                emptyTitle = "No photos or videos yet",
                emptyBody = if (partial) "You've given ProGallery access to selected items only."
                else "Photos and videos you take or download will appear here.",
                bottomPadding = barPadding,
                header = if (partial) { { PartialAccessBanner(onManageAccess) } } else null,
            )
        }
        composable("albums") {
            AlbumsScreen(vm, onOpenAlbum = { nav.navigate(Routes.collection(MediaSource.AlbumSrc(it.bucketId))) }, bottomPadding = barPadding)
        }
        composable("library") {
            LibraryScreen(
                vm,
                onOpen = { nav.navigate(Routes.collection(MediaSource.Smart(it))) },
                onOpenTrash = { nav.navigate("trash") },
                onOpenHidden = { nav.navigate("hidden") },
                onOpenSettings = { nav.navigate("settings") },
                onOpenProjects = { nav.navigate("projects") },
                bottomPadding = barPadding,
            )
        }
        composable("projects") {
            val timeline by vm.timeline.collectAsState()
            ProjectsScreen(
                pickerItems = timeline,
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate("videoproject/$id") },
                onNew = { items -> nav.navigate("videonew/" + items.joinToString(",") { it.id.toString() }) },
            )
        }
        composable("videoproject/{pid}", arguments = listOf(navArgument("pid") { type = NavType.StringType })) { entry ->
            val pid = entry.arguments?.getString("pid").orEmpty()
            val timeline by vm.timeline.collectAsState()
            VideoEditorScreen(remember(pid) { VideoOpen.Project(pid) }, timeline, onClose = { nav.popBackStack() })
        }
        composable("videonew/{ids}", arguments = listOf(navArgument("ids") { type = NavType.StringType })) { entry ->
            val ids = remember { entry.arguments?.getString("ids").orEmpty().split(',').mapNotNull { it.toLongOrNull() } }
            // Keep the order the items were picked in.
            val open = remember(ids) {
                val byId = vm.itemsByIds(ids.toSet()).associateBy { it.id }
                VideoOpen.New(ids.mapNotNull { byId[it] })
            }
            val timeline by vm.timeline.collectAsState()
            VideoEditorScreen(open, timeline, onClose = { nav.popBackStack() }, onCreated = { pid ->
                // Swap the "new" route for the saved project, so restoring the screen after the
                // app is killed reopens this project instead of creating a duplicate.
                nav.navigate("videoproject/$pid") { popUpTo("videonew/{ids}") { inclusive = true } }
            })
        }
        composable("search") {
            SearchScreen(vm, onOpen = { openViewer(MediaSource.Search, it) }, bottomPadding = barPadding)
        }
        composable("collection/{source}", arguments = listOf(navArgument("source") { type = NavType.StringType })) { entry ->
            val source = remember { MediaSource.parse(Uri.decode(entry.arguments?.getString("source").orEmpty())) }
            val itemsFlow = remember(source) { vm.itemsFor(source) }
            val entriesFlow = remember(source) { vm.entriesFor(source) }
            val items by itemsFlow.collectAsState(initial = emptyList())
            val entries by entriesFlow.collectAsState(initial = emptyList())
            val title = when (source) {
                is MediaSource.AlbumSrc -> vm.albumName(source.bucketId)
                is MediaSource.Smart -> source.collection.title
                else -> "Photos"
            }
            MediaListScreen(
                vm = vm, title = title,
                subtitle = "${items.size} item${if (items.size == 1) "" else "s"}",
                entries = entries, items = items, showBack = true, onBack = { nav.popBackStack() },
                onOpen = { openViewer(source, it) },
                emptyTitle = "Nothing here",
                emptyBody = when ((source as? MediaSource.Smart)?.collection) {
                    SmartCollection.FAVORITES -> "Tap the heart on any photo or video to add it here."
                    SmartCollection.RECENTLY_VIEWED -> "Photos and videos you open will appear here."
                    else -> "No photos or videos to show."
                },
                extraActions = {
                    if (items.isNotEmpty()) TextButton(onClick = {
                        nav.navigate(Routes.viewer(source, items.first().id, slideshow = true))
                    }) { Text("Slideshow") }
                },
            )
        }
        composable("trash") { TrashScreen(vm, onBack = { nav.popBackStack() }, onOpen = { openViewer(MediaSource.Trash, it) }) }
        composable("hidden") {
            HiddenAlbumsScreen(vm, onBack = { nav.popBackStack() },
                onOpenAlbum = { nav.navigate(Routes.collection(MediaSource.AlbumSrc(it))) })
        }
        composable("settings") {
            SettingsScreen(
                vm, onBack = { nav.popBackStack() },
                onOpenAbout = { nav.navigate("about") },
                onOpenGuide = { nav.navigate("guide") },
                onOpenHidden = { nav.navigate("hidden") },
            )
        }
        composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
        composable("guide") { GuideScreen(onBack = { nav.popBackStack() }) }
        composable(
            "viewer/{source}/{id}?slideshow={slideshow}",
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("id") { type = NavType.LongType },
                navArgument("slideshow") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val source = remember { MediaSource.parse(Uri.decode(entry.arguments?.getString("source").orEmpty())) }
            val id = entry.arguments?.getLong("id") ?: 0L
            val slideshow = entry.arguments?.getBoolean("slideshow") ?: false
            ViewerScreen(
                vm, source, id, onBack = { nav.popBackStack() }, startSlideshow = slideshow,
                onEdit = { nav.navigate("editor/${it.id}") },
            )
        }
        composable("editor/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val item = remember(id) { vm.itemsByIds(setOf(id)).firstOrNull() }
            if (item == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                val timeline by vm.timeline.collectAsState()
                if (item.isVideo) VideoEditorScreen(remember(item.id) { VideoOpen.Item(item) }, timeline, onClose = { nav.popBackStack() })
                else EditorScreen(item, onClose = { nav.popBackStack() }, onSaved = { nav.popBackStack() })
            }
        }
    }
}

@Composable
private fun PartialAccessBanner(onManage: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ProGallery can only see the items you selected.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManage) { Text("Manage") }
        }
    }
}

