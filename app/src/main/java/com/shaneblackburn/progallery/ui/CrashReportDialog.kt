package com.shaneblackburn.progallery.ui

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
 * Shown once after the app was closed by an uncaught exception. Lets the user email the exact
 * report to Shane, review it first, or dismiss (keeping the report for later in Diagnostics).
 * Nothing is ever sent automatically — the user picks the app that sends it. After a video-editor
 * crash, Safe preview mode is turned on so the device isn't stuck in a crash loop.
 */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(CrashReporter.hasPendingCrash()) }
    val autoSafe = remember { pending && CrashReporter.crashedInVideo() }
    remember { if (autoSafe) CrashReporter.enableSafePreviewAfterCrash(); 0 }
    var showFull by remember { mutableStateOf(false) }
    if (!pending) return

    val summary = CrashReporter.lastSummary() ?: "ProGallery closed unexpectedly."
    val full = remember { CrashReporter.latestReport() ?: (summary + "\n" + CrashReporter.deviceInfo()) }

    AlertDialog(
        onDismissRequest = { CrashReporter.clearPending(); pending = false },
        title = { Text("ProGallery closed unexpectedly") },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                Text(
                    buildString {
                        if (autoSafe) append("Safe preview mode has been turned on so you can keep editing (effects still apply to exported videos). The editor will try the full preview again next time you open it, and turn Safe mode back off if it works.\n\n")
                        append("You can email the report to Shane so the crash can be fixed. ")
                        append(CrashReporter.whatsIncluded())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (showFull) {
                    SelectionContainer {
                        Text(full, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                } else {
                    Text(summary, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { emailCrashReport(context); CrashReporter.clearPending(); pending = false }) {
                Text("Send Crash Report")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { showFull = true }) { Text("View Report") }
                TextButton(onClick = { CrashReporter.clearPending(); pending = false }) { Text("Not Now") }
            }
        },
    )
}
