package com.shaneblackburn.progallery

import android.content.Context
import android.os.Build
import android.util.Log
import com.shaneblackburn.progallery.core.video.SafePreviewPolicy
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a shareable file, with device and "what was happening"
 * context, so a device-specific crash (e.g. on a Samsung Galaxy Fold) can be diagnosed from
 * the exact stack trace instead of guesswork. Backed by synchronous SharedPreferences +
 * plain file writes, because DataStore/coroutines are not safe to use from a crash handler.
 *
 * It also records a lightweight "phase" (what the app was doing) and a one-shot flag so the
 * next launch can offer the report and, after a video-editor crash, suggest Safe preview mode
 * (which skips the GPU effects pipeline — the most likely culprit for a native GL/codec crash).
 *
 * NOTE: a Java handler cannot resume the app after an uncaught exception, and it cannot catch a
 * native crash (SIGSEGV in a GPU/codec driver) at all. Its job is to RECORD what happened and
 * help the app avoid the crashing path next time, not to pretend the crash didn't occur.
 */
object CrashReporter {
    private const val PREFS = "diagnostics"
    private const val K_PENDING = "pending_crash"
    private const val K_SUMMARY = "last_crash_summary"
    private const val K_CRASH_IN_VIDEO = "crash_in_video"
    private const val K_SAFE_PREVIEW = "safe_preview"
    private const val K_SAFE_AUTO = "safe_preview_auto"
    private const val K_SAFE_CRASH_COUNT = "safe_preview_crash_count"
    private const val K_PHASE = "last_phase"
    private const val MAX_FILES = 6

    @Volatile private var phase: String = "startup"
    private var appCtx: Context? = null

    fun install(context: Context) {
        appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(thread, throwable) }
                .onFailure { Log.e("CrashReporter", "failed to write crash report", it) }
            // Chain to the platform handler so the process still terminates as usual.
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Records what the app is doing, so a crash report says where it happened. */
    fun setPhase(tag: String) {
        phase = tag
        runCatching { prefs()?.edit()?.putString(K_PHASE, tag)?.apply() }
    }

    private fun prefs() = appCtx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun crashDir(): File? = appCtx?.let { File(it.filesDir, "crashes").apply { mkdirs() } }

    private fun writeReport(thread: Thread, throwable: Throwable) {
        val ctx = appCtx ?: return
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)
        val report = CrashReportText.body(
            time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now),
            appVersion = appVersion(ctx),
            deviceLine = deviceLine(),
            androidLine = androidLine(),
            phase = phase,
            threadName = thread.name,
            safeMode = isSafePreview(),
            stackTrace = Log.getStackTraceString(throwable),
        )
        crashDir()?.let { dir ->
            runCatching { File(dir, "crash-$stamp.txt").writeText(report) }
            // Keep only the most recent few reports.
            runCatching {
                dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
            }
        }
        prefs()?.edit()
            ?.putBoolean(K_PENDING, true)
            ?.putString(K_SUMMARY, "${throwable.javaClass.simpleName}: ${throwable.message ?: "(no message)"}  ·  while: $phase")
            ?.putBoolean(K_CRASH_IN_VIDEO, phase.startsWith("video"))
            ?.commit()
    }

    fun deviceLine(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return "device  : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}); soc: $soc"
    }

    fun androidLine(): String = "android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun deviceInfo(): String = deviceLine() + "\n" + androidLine()

    /** Email subject naming the device, e.g. "ProGallery Crash Report - samsung SM-F946B". */
    fun emailSubject(): String = CrashReportText.subject(Build.MANUFACTURER ?: "", Build.MODEL ?: "")

    fun whatsIncluded(): String = CrashReportText.whatsIncluded()
    fun nativeNote(): String = CrashReportText.nativeNote()
    const val EMAIL: String = CrashReportText.EMAIL

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        "${pi.versionName} (code $code)"
    }.getOrDefault("unknown")

    // ---- state for the UI ----

    fun hasPendingCrash(): Boolean = prefs()?.getBoolean(K_PENDING, false) ?: false
    fun lastSummary(): String? = prefs()?.getString(K_SUMMARY, null)
    fun crashedInVideo(): Boolean = prefs()?.getBoolean(K_CRASH_IN_VIDEO, false) ?: false

    fun clearPending() {
        runCatching { prefs()?.edit()?.remove(K_PENDING)?.remove(K_CRASH_IN_VIDEO)?.commit() }
    }

    /** The newest crash report's text, or null. Includes device info + stack trace. */
    fun latestReport(): String? = runCatching {
        crashDir()?.listFiles()?.maxByOrNull { it.lastModified() }?.readText()
    }.getOrNull()

    fun latestReportFile(): File? = runCatching {
        crashDir()?.listFiles()?.maxByOrNull { it.lastModified() }
    }.getOrNull()

    fun clearReports() {
        runCatching { crashDir()?.listFiles()?.forEach { it.delete() } }
        clearPending()
    }

    /** When on, the video editor preview skips the GPU effects pipeline (CompositionPlayer). */
    fun isSafePreview(): Boolean = prefs()?.getBoolean(K_SAFE_PREVIEW, false) ?: false

    /** Whether Safe mode was enabled automatically (after a crash) rather than chosen by the user. Only
     * auto-enabled Safe mode is subject to the guarded recovery trial; a manual choice is never auto-cleared. */
    fun isSafePreviewAuto(): Boolean = prefs()?.getBoolean(K_SAFE_AUTO, false) ?: false

    /** Consecutive editor crashes with no confirmed full-effects success since; drives the trial back-off. */
    fun safePreviewCrashCount(): Int = prefs()?.getInt(K_SAFE_CRASH_COUNT, 0) ?: 0

    /** Auto-enable Safe mode after an editor crash, counting the crash for the trial back-off. */
    fun enableSafePreviewAfterCrash() {
        runCatching {
            prefs()?.edit()
                ?.putBoolean(K_SAFE_PREVIEW, true)
                ?.putBoolean(K_SAFE_AUTO, true)
                ?.putInt(K_SAFE_CRASH_COUNT, safePreviewCrashCount() + 1)
                ?.commit()
        }
    }

    /** The user set Safe mode explicitly. A manual choice is never auto-overridden; turning it off also
     * clears the crash back-off. */
    fun setSafePreviewManual(on: Boolean) {
        runCatching {
            val e = prefs()?.edit()?.putBoolean(K_SAFE_PREVIEW, on)?.putBoolean(K_SAFE_AUTO, false)
            if (!on) e?.putInt(K_SAFE_CRASH_COUNT, 0)
            e?.commit()
        }
    }

    /** Clear an AUTO-enabled Safe mode after a confirmed successful full-effects session. No-op when Safe
     * mode was set manually, so the user's choice always stands. */
    fun clearSafePreviewAfterSuccess() {
        if (!isSafePreviewAuto()) return
        runCatching {
            prefs()?.edit()
                ?.putBoolean(K_SAFE_PREVIEW, false)
                ?.putBoolean(K_SAFE_AUTO, false)
                ?.putInt(K_SAFE_CRASH_COUNT, 0)
                ?.commit()
        }
    }

    /** Whether the editor should attempt the full-effects pipeline as a guarded recovery trial on open. */
    fun shouldRecoveryTrial(): Boolean = SafePreviewPolicy.shouldTrial(
        isSafePreview(), isSafePreviewAuto(), safePreviewCrashCount(),
    )

    /** Kept for compatibility; prefer the typed helpers above. */
    fun setSafePreview(on: Boolean) { runCatching { prefs()?.edit()?.putBoolean(K_SAFE_PREVIEW, on)?.commit() } }
}
