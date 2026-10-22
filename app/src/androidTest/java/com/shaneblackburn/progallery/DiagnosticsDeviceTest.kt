package com.shaneblackburn.progallery

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaneblackburn.progallery.ui.buildCrashEmailIntent
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Instrumented checks for the crash-report email/share plumbing: the FileProvider is configured
 * so a report can be attached, and the email intent carries the right recipient/subject/body.
 * These compile and are ready to run; they have NOT been run on a physical Samsung device.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fileProviderExposesCrashReportsReadOnly() {
        val dir = File(ctx.filesDir, "crashes").apply { mkdirs() }
        val f = File(dir, "crash-test.txt").apply { writeText("report body") }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            assertTrue(uri.toString().startsWith("content://${ctx.packageName}.fileprovider/"), "uri: $uri")
            // An email app reads the attachment exactly like this.
            ctx.contentResolver.openInputStream(uri)!!.use {
                assertEquals("report body", it.readBytes().decodeToString())
            }
        } finally { f.delete() }
    }

    @Test fun deviceInfoAndSubjectArePopulated() {
        assertTrue(CrashReporter.deviceInfo().contains("android"), CrashReporter.deviceInfo())
        assertTrue(CrashReporter.emailSubject().startsWith("ProGallery Crash Report"), CrashReporter.emailSubject())
    }

    @Test fun emailIntentHasRecipientSubjectAndBody() {
        val intent = buildCrashEmailIntent(ctx)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        val to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL)
        assertNotNull(to); assertEquals("shanebl030@gmail.com", to!!.single())
        assertTrue(intent.getStringExtra(Intent.EXTRA_SUBJECT)!!.startsWith("ProGallery Crash Report"))
        assertNotNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun emailIntentAttachesReportWhenOnePresent() {
        val dir = File(ctx.filesDir, "crashes").apply { mkdirs() }
        val f = File(dir, "crash-20260101-000000.txt").apply { writeText("ProGallery crash report\nstacktrace") }
        try {
            val intent = buildCrashEmailIntent(ctx)
            val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            // A report exists, so it should be attached via the FileProvider.
            assertNotNull(stream, "expected an attached report")
            assertTrue(stream.toString().contains("${ctx.packageName}.fileprovider"))
        } finally { f.delete() }
    }
}
