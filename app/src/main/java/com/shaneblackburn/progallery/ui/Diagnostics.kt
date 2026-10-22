package com.shaneblackburn.progallery.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.shaneblackburn.progallery.CrashReporter

/**
 * Shared crash-report actions (email / share / copy) used by both the next-launch crash dialog
 * and Settings › Diagnostics. Everything is user-initiated and goes through the user's chosen app
 * via a standard intent — ProGallery itself has no INTERNET permission and never sends anything.
 */

/** Builds the email intent (recipient, subject, body, and the report as a .txt attachment). */
fun buildCrashEmailIntent(context: Context): Intent {
    val text = CrashReporter.latestReport() ?: fallbackText()
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(CrashReporter.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, CrashReporter.emailSubject())
        putExtra(Intent.EXTRA_TEXT, text) // readable body, also the fallback if the attachment is dropped
        attachReport(context, this)
    }
}

/** Email the latest crash report to the fixed address, attaching it as a .txt where supported. */
fun emailCrashReport(context: Context) {
    val intent = buildCrashEmailIntent(context)
    launchOrFallback(context, intent, "Send crash report to Shane", CrashReporter.latestReport() ?: fallbackText())
}

/** Generic share of the latest crash report (any app, incl. save to Files/Drive). */
fun shareCrashReport(context: Context) {
    val text = CrashReporter.latestReport() ?: fallbackText()
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, CrashReporter.emailSubject())
        putExtra(Intent.EXTRA_TEXT, text)
        attachReport(context, this)
    }
    launchOrFallback(context, intent, "Share crash report", text)
}

private fun fallbackText(): String =
    "ProGallery diagnostics (no crash report saved)\n" + CrashReporter.deviceInfo()

private fun attachReport(context: Context, intent: Intent) {
    val file = CrashReporter.latestReportFile() ?: return
    if (!file.exists()) return
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun launchOrFallback(context: Context, intent: Intent, title: String, text: String) {
    if (intent.resolveActivity(context.packageManager) != null) {
        runCatching { context.startActivity(Intent.createChooser(intent, title)) }
            .onFailure { copyToClipboard(context, text, "No app could send it — report copied to clipboard.") }
    } else {
        // Requirement: if nothing can send/share, still give the user the report.
        copyToClipboard(context, text, "No email or share app found — report copied to clipboard (also in Settings › Diagnostics).")
    }
}

fun copyToClipboard(context: Context, text: String, message: String = "Crash report copied") {
    runCatching {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("ProGallery crash report", text))
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

/**
 * A dialog that shows the most recent crash report (or device info if none) with Email / Share /
 * Copy actions. Opened from Settings › Diagnostics, so a report stays reachable days later.
 */
@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val report = CrashReporter.latestReport()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics") },
        text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                Text(
                    if (report != null) "Most recent crash report. ${CrashReporter.whatsIncluded()}"
                    else "No crash has been recorded. You can still send your device details. ${CrashReporter.nativeNote()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        report ?: fallbackText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { emailCrashReport(context) }) { Text("Email") } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = { shareCrashReport(context) }) { Text("Share") }
                TextButton(onClick = { copyToClipboard(context, report ?: fallbackText()) }) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
