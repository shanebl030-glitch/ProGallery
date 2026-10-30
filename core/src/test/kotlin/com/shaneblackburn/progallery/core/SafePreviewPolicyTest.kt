package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.video.SafePreviewPolicy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafePreviewPolicyTest {

    @Test fun noSafeModeNeverTrialsOrSkips() {
        assertFalse(SafePreviewPolicy.shouldTrial(safeOn = false, auto = false, crashCount = 0))
        assertFalse(SafePreviewPolicy.skipFullEffects(safeOn = false, auto = false, crashCount = 0))
        // Even if flags are stale, safeOn=false means full effects run.
        assertFalse(SafePreviewPolicy.skipFullEffects(safeOn = false, auto = true, crashCount = 5))
    }

    @Test fun autoSafeModeTrialsWhileUnderCrashLimit() {
        // Fresh auto-enable (one crash): trial full effects, don't skip.
        assertTrue(SafePreviewPolicy.shouldTrial(safeOn = true, auto = true, crashCount = 0))
        assertTrue(SafePreviewPolicy.shouldTrial(safeOn = true, auto = true, crashCount = 1))
        assertFalse(SafePreviewPolicy.skipFullEffects(safeOn = true, auto = true, crashCount = 1))
    }

    @Test fun autoSafeModeStopsTrialingAfterRepeatedCrashes() {
        // At/over the limit: back off — stay in safe mode, don't keep crashing.
        assertFalse(SafePreviewPolicy.shouldTrial(safeOn = true, auto = true, crashCount = SafePreviewPolicy.MAX_TRIAL_CRASHES))
        assertTrue(SafePreviewPolicy.skipFullEffects(safeOn = true, auto = true, crashCount = SafePreviewPolicy.MAX_TRIAL_CRASHES))
        assertTrue(SafePreviewPolicy.skipFullEffects(safeOn = true, auto = true, crashCount = 9))
    }

    @Test fun manualSafeModeNeverTrials() {
        // Manually enabled (auto=false): the user's choice stands — never auto-trial or auto-clear.
        assertFalse(SafePreviewPolicy.shouldTrial(safeOn = true, auto = false, crashCount = 0))
        assertTrue(SafePreviewPolicy.skipFullEffects(safeOn = true, auto = false, crashCount = 0))
    }
}
