package com.shaneblackburn.progallery.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.query.GridEntry
import com.shaneblackburn.progallery.ui.GalleryViewModel
import com.shaneblackburn.progallery.ui.components.EmptyView
import com.shaneblackburn.progallery.ui.components.MediaGrid
import com.shaneblackburn.progallery.ui.components.SelectionActionBar
import com.shaneblackburn.progallery.ui.components.SelectionTopBar
import java.util.Calendar

private val QUICK_FILTERS = listOf(
    "Videos" to "videos",
    "Favourites" to "fav",
    "Screenshots" to "screenshots",
    "Downloads" to "downloads",
    "GIFs" to "gif",
    "RAW" to "raw",
    "Over 10 MB" to ">10mb",
)

@Composable
fun SearchScreen(
    vm: GalleryViewModel,
    onOpen: (MediaItem) -> Unit,
    bottomPadding: PaddingValues,
) {
    val text by vm.searchText.collectAsState()
    val results by vm.searchResults.collectAsState()
    val settings by vm.settings.collectAsState()
    val selection by vm.selection.collectAsState()
    val focus = LocalFocusManager.current
    val entries = remember(results) { results.mapIndexed { i, m -> GridEntry.Item(m, i) } }
    val ids = remember(results) { results.mapTo(HashSet()) { it.id } }
    val activeSelection = remember(selection, ids) { selection.filterTo(HashSet()) { it in ids } }
    val selecting = activeSelection.isNotEmpty()
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }

    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (selecting) {
                SelectionTopBar(activeSelection.size, vm::clearSelection) { vm.selectAll(ids) }
            } else {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { vm.searchText.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search name, album, date, type…") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (text.isNotEmpty()) IconButton(onClick = { vm.searchText.value = "" }) {
                                Icon(Icons.Filled.Close, "Clear search")
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (QUICK_FILTERS + listOf("This year" to "$thisYear", "Last year" to "${thisYear - 1}")).forEach { (label, token) ->
                            AssistChip(
                                onClick = {
                                    val current = vm.searchText.value.trim()
                                    vm.searchText.value = if (current.split(' ').contains(token)) current
                                    else listOf(current, token).filter { it.isNotBlank() }.joinToString(" ")
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (selecting) SelectionActionBar(vm, results.filter { it.id in activeSelection })
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
            when {
                text.isBlank() -> EmptyView(
                    "Search your gallery",
                    "Try “beach”, “videos 2024”, “june”, “album:camera”, “>20mb”, “ext:png” or “after:2025-01-01”.",
                    icon = Icons.Outlined.Search,
                )
                results.isEmpty() -> EmptyView("No matches", "Nothing matched “$text”.", icon = Icons.Outlined.Search)
                else -> Column {
                    Text(
                        "${results.size} result${if (results.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                    MediaGrid(
                        entries = entries,
                        columns = settings.gridColumns,
                        selection = activeSelection,
                        onOpen = { item, _ -> focus.clearFocus(); onOpen(item) },
                        onToggle = vm::toggleSelect,
                        onSetSelection = vm::setSelection,
                        onColumnsChange = vm::setGridColumns,
                        contentPadding = PaddingValues(
                            bottom = if (selecting) inner.calculateBottomPadding() else bottomPadding.calculateBottomPadding(),
                        ),
                    )
                }
            }
        }
    }
}
