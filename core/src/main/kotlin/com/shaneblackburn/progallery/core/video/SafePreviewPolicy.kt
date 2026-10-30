package com.shaneblackburn.progallery.core.video

/**
 * Pure decision logic for Safe Preview Mode auto-recovery (kept out of Android/prefs code so it can be
 * unit-tested without a device).
 *
 * Safe Preview Mode skips the full-effects CompositionPlayer and previews with the raw player. It is
 * turned on automatically after an editor crash so a capable device isn't left in a crash loop — but
 * it must not leave that device permanently degraded. The rules, matching Shane's spec:
 *
 * - **Auto-enabled** Safe mode is given a guarded recovery TRIAL: the next editor session attempts the
 *   full-effects pipeline instead of forcing the raw player.
 * - It is cleared ONLY on a **confirmed successful full-effects session**: CompositionPlayer initialises
 *   (stays in full mode), actually renders a frame, and remains stable for [STABILITY_MS] after that
 *   first frame. Merely staying open, or gracefully falling back to the raw player, does NOT clear it.
 * - A crash during the trial re-arms Safe mode and increments a counter; after [MAX_TRIAL_CRASHES]
 *   consecutive crashes with no confirmed success, auto-trialing stops (stay safe) to avoid a crash loop.
 * - **Manually** set Safe mode (on or off) is never auto-overridden.
 */
object SafePreviewPolicy {
    /** Consecutive full-effects crashes (no confirmed success in between) after which trialing stops. */
    const val MAX_TRIAL_CRASHES = 2

    /** How long a full-effects preview must stay stable AFTER its first rendered frame to count as a
     * confirmed success (ms). */
    const val STABILITY_MS = 5_000L

    /**
     * Whether to attempt the full-effects pipeline as a recovery trial on editor open. Only when Safe
     * mode is on, was set automatically (not by the user), and we haven't exhausted the crash back-off.
     */
    fun shouldTrial(safeOn: Boolean, auto: Boolean, crashCount: Int): Boolean =
        safeOn && auto && crashCount < MAX_TRIAL_CRASHES

    /**
     * The effective "skip the full-effects pipeline" decision for the preview on editor open: skip when
     * Safe mode is on AND this is not a recovery trial (i.e. manual Safe mode, or the back-off is spent).
     */
    fun skipFullEffects(safeOn: Boolean, auto: Boolean, crashCount: Int): Boolean =
        safeOn && !shouldTrial(safeOn, auto, crashCount)
}
