package com.shaneblackburn.progallery.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.model.AlbumSort
import com.shaneblackburn.progallery.core.query.GroupBy
import com.shaneblackburn.progallery.core.query.SortField
import com.shaneblackburn.progallery.core.query.SortSpec
import com.shaneblackburn.progallery.data.AppSettings
import kotlin.math.roundToInt

private val SORT_LABELS = listOf(
    SortField.DATE_TAKEN to "Date taken",
    SortField.DATE_MODIFIED to "Date modified",
    SortField.DATE_ADDED to "Date added",
    SortField.NAME to "Name",
    SortField.SIZE to "Size",
    SortField.TYPE to "Type",
    SortField.DURATION to "Duration",
    SortField.RESOLUTION to "Resolution",
)

/** Sort, grouping and grid-size options for media grids. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MediaViewOptionsSheet(
    settings: AppSettings,
    onSort: (SortSpec) -> Unit,
    onGroupBy: (GroupBy) -> Unit,
    onColumns: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            SheetTitle("Sort by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SORT_LABELS.forEach { (field, label) ->
                    val selected = settings.sort.field == field
                    FilterChip(
                        selected = selected,
                        onClick = { onSort(settings.sort.copy(field = field)) },
                        label = { Text(label) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, null, Modifier.padding(0.dp)) }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val (ascLabel, descLabel) = when (settings.sort.field) {
                SortField.NAME, SortField.TYPE -> "A → Z" to "Z → A"
                SortField.SIZE, SortField.DURATION, SortField.RESOLUTION -> "Smallest" to "Largest"
                else -> "Oldest" to "Newest"
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !settings.sort.ascending,
                    onClick = { onSort(settings.sort.copy(ascending = false)) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("$descLabel first") }
                SegmentedButton(
                    selected = settings.sort.ascending,
                    onClick = { onSort(settings.sort.copy(ascending = true)) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("$ascLabel first") }
            }

            if (settings.sort.isDateBased) {
                SheetTitle("Group by")
                val groups = listOf(GroupBy.DAY to "Day", GroupBy.MONTH to "Month", GroupBy.YEAR to "Year", GroupBy.NONE to "None")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    groups.forEachIndexed { i, (g, label) ->
                        SegmentedButton(
                            selected = settings.groupBy == g,
                            onClick = { onGroupBy(g) },
                            shape = SegmentedButtonDefaults.itemShape(i, groups.size),
                        ) { Text(label) }
                    }
                }
            }

            SheetTitle("Grid size: ${settings.gridColumns} per row")
            Slider(
                value = settings.gridColumns.toFloat(),
                onValueChange = { onColumns(it.roundToInt()) },
                valueRange = AppSettings.MIN_COLUMNS.toFloat()..AppSettings.MAX_COLUMNS.toFloat(),
                steps = AppSettings.MAX_COLUMNS - AppSettings.MIN_COLUMNS - 1,
            )
            Text(
                "Tip: pinch the grid to change size.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumViewOptionsSheet(
    sort: AlbumSort,
    columns: Int,
    onSort: (AlbumSort) -> Unit,
    onColumns: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            SheetTitle("Sort albums by")
            val opts = listOf(AlbumSort.RECENT to "Recent", AlbumSort.NAME to "Name", AlbumSort.COUNT to "Items", AlbumSort.SIZE to "Size")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                opts.forEachIndexed { i, (s, label) ->
                    SegmentedButton(selected = sort == s, onClick = { onSort(s) },
                        shape = SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(label) }
                }
            }
            SheetTitle("Albums per row")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                (2..4).forEachIndexed { i, n ->
                    SegmentedButton(selected = columns == n, onClick = { onColumns(n) },
                        shape = SegmentedButtonDefaults.itemShape(i, 3)) { Text("$n") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Row(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
