package com.shaneblackburn.progallery.video

/**
 * Pure policy for the export foreground service, extracted so it can be unit-tested on the JVM
 * (the service class itself needs the Android framework).
 */
object ExportServicePolicy {
    /**
     * The service may hold a foreground notification only while an export is actually running.
     * In any other state — Idle / Done / Cancelled / Failed, e.g. a background or system restart
     * of the service on a fresh process — it must NOT call startForeground(): on Android 14+ (and
     * strictly on 15/16) starting a foreground service from the background is refused with
     * ForegroundServiceStartNotAllowedException, which crashes the app. This was the Galaxy Z
     * Fold5 (Android 16) crash.
     */
    fun shouldRunForeground(state: ExportState): Boolean = state is ExportState.Running
}
