package com.shaneblackburn.progallery

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JVM tests for the crash-report / email formatting (no Android dependencies). */
class CrashReportTextTest {

    @Test fun subjectNamesTheDevice() {
        assertEquals("ProGallery Crash Report - samsung SM-F946B", CrashReportText.subject("samsung", "SM-F946B"))
        // Blank parts are dropped gracefully.
        assertEquals("ProGallery Crash Report - Pixel 8", CrashReportText.subject("", "Pixel 8"))
        assertEquals("ProGallery Crash Report", CrashReportText.subject("", ""))
    }

    @Test fun recipientIsFixed() {
        assertEquals("shanebl030@gmail.com", CrashReportText.EMAIL)
    }

    @Test fun whatsIncludedIsHonestAboutPrivacy() {
        val t = CrashReportText.whatsIncluded().lowercase()
        // Must say it excludes personal media and never sends automatically.
        assertTrue("photos" in t && "videos" in t && "projects" in t, "names what is excluded")
        assertTrue("automatically" in t, "states nothing is sent automatically")
    }

    @Test fun bodyContainsEveryRequiredField() {
        val body = CrashReportText.body(
            time = "2026-10-22 19:30:00",
            appVersion = "0.4.3 (code 9)",
            deviceLine = "device  : samsung SM-F946B (q5q); soc: Qualcomm SM8550",
            androidLine = "android : 14 (API 34)",
            phase = "video:preview-full",
            threadName = "main",
            safeMode = false,
            stackTrace = "java.lang.IllegalStateException: boom\n\tat com.example.Foo.bar(Foo.kt:42)",
        )
        assertContains(body, "0.4.3 (code 9)")          // app version
        assertContains(body, "SM-F946B")                 // device make/model
        assertContains(body, "API 34")                   // Android version
        assertContains(body, "video:preview-full")       // editing operation
        assertContains(body, "IllegalStateException: boom") // exception + message
        assertContains(body, "Foo.kt:42")                // full stack trace
    }
}
