package com.shaneblackburn.progallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.CrashReporter

/**
 * Shown once after the app was closed by an uncaught exception. Lets the user read, copy or
 * share the exact crash report (device + stack trace) so a device-specific crash can be
 * diagnosed without adb. After a video-editor crash, Safe preview mode is turned on so the
 * device isn't stuck in a crash loop.
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(CrashReporter.hasPendingCrash()) }
    // If the crash happened while editing video, switch on Safe preview mode automatically.
    val autoSafe = remember { pending && CrashReporter.crashedInVideo() }
    remember { if (autoSafe) CrashReporter.setSafePreview(true); 0 }
    var showFull by remember { mutableStateOf(false) }
    if (!pending) return

    val summary = CrashReporter.lastSummary() ?: "ProGallery closed unexpectedly."
    val full = remember { CrashReporter.latestReport() ?: (summary + "\n" + CrashReporter.deviceInfo()) }

    AlertDialog(
        onDismissRequest = { CrashReporter.clearPending(); pending = false },
        title = { Text("ProGallery closed unexpectedly") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (autoSafe) "Safe preview mode has been turned on so you can keep editing (effects still apply to exported videos). Please send this report so the crash can be fixed:"
                    else "Please send this report so the crash can be fixed:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    Text(
                        if (showFull) full else summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (!showFull) TextButton(onClick = { showFull = true }) { Text("Show full report") }
            }
        },
        confirmButton = {
            TextButton(onClick = { shareText(context, full) }) { Text("Share report") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { copyText(context, full) }) { Text("Copy") }
                TextButton(onClick = { CrashReporter.clearPending(); pending = false }) { Text("Dismiss") }
            }
        },
    )
}

private fun shareText(context: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ProGallery crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share crash report"))
    }.onFailure { Toast.makeText(context, "Couldn't open share sheet", Toast.LENGTH_SHORT).show() }
}

private fun copyText(context: Context, text: String) {
    runCatching {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("ProGallery crash report", text))
        Toast.makeText(context, "Crash report copied", Toast.LENGTH_SHORT).show()
    }
}
