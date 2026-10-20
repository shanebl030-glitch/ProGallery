package com.shaneblackburn.progallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.query.GridEntry
import com.shaneblackburn.progallery.ui.GalleryViewModel
import com.shaneblackburn.progallery.ui.LibraryState
import com.shaneblackburn.progallery.ui.components.EmptyView
import com.shaneblackburn.progallery.ui.components.LoadingView
import com.shaneblackburn.progallery.ui.components.MediaGrid
import com.shaneblackburn.progallery.ui.components.MediaViewOptionsSheet
import com.shaneblackburn.progallery.ui.components.SelectionActionBar
import com.shaneblackburn.progallery.ui.components.SelectionTopBar

/**
 * Shared screen for any grid of media: the Photos timeline, an album, a smart
 * collection. Handles selection mode, the view-options sheet and empty/loading states.
 *
 * @param showBack false for top-level tabs.
 * @param extraActions extra top-bar actions (shown outside selection mode).
 * @param bottomPadding space reserved for the parent's bottom navigation bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaListScreen(
    vm: GalleryViewModel,
    title: String,
    subtitle: String?,
    entries: List<GridEntry>,
    items: List<MediaItem>,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    emptyTitle: String,
    emptyBody: String,
    bottomPadding: PaddingValues = PaddingValues(),
    extraActions: @Composable () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    selectionActions: (@Composable (List<MediaItem>) -> Unit)? = null,
) {
    val library by vm.library.collectAsState()
    val settings by vm.settings.collectAsState()
    val selection by vm.selection.collectAsState()
    var showOptions by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // Only keep selected ids that are still in this list (items can disappear after delete).
    val visibleIds = remember(items) { items.mapTo(HashSet()) { it.id } }
    val activeSelection = remember(selection, visibleIds) { selection.filterTo(HashSet()) { it in visibleIds } }
    val selectedItems = remember(activeSelection, items) { items.filter { it.id in activeSelection } }
    val selecting = activeSelection.isNotEmpty()

    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selecting) {
                SelectionTopBar(
                    count = activeSelection.size,
                    onClose = vm::clearSelection,
                    onSelectAll = { vm.selectAll(visibleIds) },
                )
            } else {
                TopAppBar(
                    title = {
                        androidx.compose.foundation.layout.Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) {
                                Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    navigationIcon = {
                        if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        extraActions()
                        IconButton(onClick = { showOptions = true }) { Icon(Icons.Outlined.Tune, "View options") }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = {
            if (selecting) {
                if (selectionActions != null) selectionActions(selectedItems) else SelectionActionBar(vm, selectedItems)
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            when {
                library is LibraryState.Loading -> LoadingView()
                items.isEmpty() -> EmptyView(emptyTitle, emptyBody)
                else -> MediaGrid(
                    entries = entries,
                    columns = settings.gridColumns,
                    selection = activeSelection,
                    onOpen = { item, _ -> onOpen(item) },
                    onToggle = vm::toggleSelect,
                    onSetSelection = { ids -> vm.setSelection(ids) },
                    onColumnsChange = vm::setGridColumns,
                    state = gridState,
                    contentPadding = PaddingValues(
                        bottom = if (selecting) inner.calculateBottomPadding() else bottomPadding.calculateBottomPadding(),
                    ),
                    header = header,
                )
            }
        }
    }

    if (showOptions) {
        MediaViewOptionsSheet(
            settings = settings,
            onSort = vm::setSort,
            onGroupBy = vm::setGroupBy,
            onColumns = vm::setGridColumns,
            onDismiss = { showOptions = false },
        )
    }
}
