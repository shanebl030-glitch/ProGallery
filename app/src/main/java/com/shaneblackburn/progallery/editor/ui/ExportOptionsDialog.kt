package com.shaneblackburn.progallery.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.editor.ExportFormat
import com.shaneblackburn.progallery.editor.ExportOptions
import kotlin.math.roundToInt

private val SIZES = listOf(0 to "Original", 4096 to "4096 px", 2048 to "2048 px", 1080 to "1080 px")

@Composable
fun ExportOptionsDialog(options: ExportOptions, onChange: (ExportOptions) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Label("Format")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ExportFormat.entries.forEachIndexed { i, f ->
                        SegmentedButton(options.format == f, { onChange(options.copy(format = f)) },
                            SegmentedButtonDefaults.itemShape(i, ExportFormat.entries.size)) { Text(f.label) }
                    }
                }
                if (options.format != ExportFormat.PNG) {
                    Label("Quality: ${options.quality}")
                    Slider(
                        value = options.quality.toFloat(),
                        onValueChange = { onChange(options.copy(quality = it.roundToInt())) },
                        valueRange = 50f..100f,
                    )
                }
                Label("Size (longest side)")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SIZES.forEachIndexed { i, (px, label) ->
                        SegmentedButton(options.maxSide == px, { onChange(options.copy(maxSide = px)) },
                            SegmentedButtonDefaults.itemShape(i, SIZES.size)) { Text(label, maxLines = 1) }
                    }
                }
                Check("Keep photo details (date, camera)", options.keepMetadata) { onChange(options.copy(keepMetadata = it)) }
                if (options.keepMetadata) {
                    Check("Remove location", options.removeLocation) { onChange(options.copy(removeLocation = it)) }
                }
                Text(
                    "Details are kept for JPEG copies. \"Replace original\" always keeps the original's file format.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun Check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = onChange)
        Text(text)
    }
}
