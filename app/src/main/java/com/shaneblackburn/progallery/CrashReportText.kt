package com.shaneblackburn.progallery

/**
 * Pure (no Android) formatting for crash reports and the email that sends them, so the content,
 * the email subject and the privacy wording are unit-testable on the JVM. [CrashReporter] gathers
 * the real device/version values and calls these.
 */
object CrashReportText {

    const val EMAIL = "shanebl030@gmail.com"

    /** Email subject, e.g. "ProGallery Crash Report - samsung SM-F946B". */
    fun subject(manufacturer: String, model: String): String {
        val device = listOf(manufacturer.trim(), model.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        return "ProGallery Crash Report" + if (device.isNotEmpty()) " - $device" else ""
    }

    /** What the report contains / does not contain — shown before the user sends it. */
    fun whatsIncluded(): String =
        "The report contains only: the error and its full stack trace, your device make/model, " +
            "Android version, the app version, and which editing step was running. It does NOT " +
            "contain your photos, videos, projects or any personal media. Nothing is ever sent " +
            "automatically — it only leaves your phone when you choose an app to send it with."

    /** Some crashes happen in native code before a report can be written. */
    fun nativeNote(): String =
        "Note: some low-level (native) crashes can close the app before a report can be saved. " +
            "If no report appears after a crash, a device logcat is needed instead."

    /**
     * The full report text (also used as the email body / attachment contents). All inputs are
     * pre-gathered strings so this stays pure and testable.
     */
    fun body(
        time: String,
        appVersion: String,
        deviceLine: String,
        androidLine: String,
        phase: String,
        threadName: String,
        safeMode: Boolean,
        stackTrace: String,
    ): String = buildString {
        appendLine("ProGallery crash report")
        appendLine("time    : $time")
        appendLine("app     : $appVersion")
        appendLine(deviceLine)
        appendLine(androidLine)
        appendLine("doing   : $phase")
        appendLine("thread  : $threadName")
        appendLine("safeMode: $safeMode")
        appendLine()
        append(stackTrace)
    }
}
