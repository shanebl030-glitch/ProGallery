package com.shaneblackburn.progallery

import com.shaneblackburn.progallery.video.ExportServicePolicy
import com.shaneblackburn.progallery.video.ExportState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The export service must only go foreground while an export is actually running. A background or
 * system restart of the service (any non-Running state) must not, or Android 14+/16 refuses the
 * foreground start and crashes the app — the Galaxy Z Fold5 crash.
 */
class ExportServicePolicyTest {
    @Test fun foregroundOnlyWhileRunning() {
        assertTrue(ExportServicePolicy.shouldRunForeground(ExportState.Running(0.3f, "clip")))
        assertFalse(ExportServicePolicy.shouldRunForeground(ExportState.Idle))
        assertFalse(ExportServicePolicy.shouldRunForeground(ExportState.Cancelled))
        assertFalse(ExportServicePolicy.shouldRunForeground(ExportState.Failed("boom")))
    }
}
