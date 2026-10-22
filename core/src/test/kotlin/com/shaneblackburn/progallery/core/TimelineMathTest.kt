package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.TimelineMath
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the timeline time<->scroll conversion and the frame-step/locate timing model that the
 * preview playhead relies on. These run on the JVM; the on-device test [VideoProjectDeviceTest]
 * checks that the live CompositionPlayer position actually tracks this model.
 */
class TimelineMathTest {
    private fun clip(ms: Long, fps: Int = 30, speed: Float = 1f) =
        Clip(id = 1, uri = "u", name = "c", sourceDurationMs = ms, width = 1920, height = 1080,
            frameRate = fps, speed = speed)

    @Test fun scrollAndMsAreInverse() {
        val pxPerMs = 0.06f
        val duration = 30_000L
        // For every whole second, ms -> px -> ms must round-trip to within one frame (the pixel
        // quantisation error), never a growing offset.
        var t = 0L
        while (t <= duration) {
            val px = TimelineMath.scrollPxForMs(t, pxPerMs)
            val back = TimelineMath.msForScrollPx(px, pxPerMs, duration)
            assertTrue(abs(back - t) <= (1f / pxPerMs).toLong() + 1,
                "round-trip at ${t}ms drifted: px=$px back=${back}ms")
            t += 1000
        }
    }

    @Test fun msForScrollPxIsClampedToProject() {
        val pxPerMs = 0.06f
        val duration = 10_000L
        assertEquals(0L, TimelineMath.msForScrollPx(-500, pxPerMs, duration), "negative scroll clamps to 0")
        assertEquals(duration, TimelineMath.msForScrollPx(Int.MAX_VALUE, pxPerMs, duration), "overscroll clamps to duration")
    }

    @Test fun zoomDoesNotChangeTheTimeUnderThePlayhead() {
        // The time under the playhead depends only on scroll/pxPerMs, so zooming (changing pxPerMs)
        // while keeping the same position must map back to (near) the same scroll pixel ratio.
        val duration = 20_000L
        val posMs = 7_500L
        for (pxPerMs in floatArrayOf(0.005f, 0.02f, 0.06f, 0.2f, 0.6f)) {
            val px = TimelineMath.scrollPxForMs(posMs, pxPerMs)
            val back = TimelineMath.msForScrollPx(px, pxPerMs, duration)
            assertTrue(abs(back - posMs) <= (1f / pxPerMs).toLong() + 1,
                "zoom pxPerMs=$pxPerMs lost the position: $posMs -> px $px -> $back")
        }
    }

    @Test fun frameStepAdvancesByOneDisplayedFrame() {
        val p = VideoProject(clips = listOf(clip(ms = 10_000, fps = 30)))
        val one = p.stepFrames(0L, 1)
        assertEquals(33L, one, "30fps: one frame ~= 33ms")
        // Stepping forward then back returns to (near) the start — no accumulated drift.
        val there = p.stepFrames(5_000L, 1)
        val back = p.stepFrames(there, -1)
        assertEquals(5_000L, back, "frame step is reversible")
    }

    @Test fun frameStepRespectsSpeedAndClamps() {
        val fast = VideoProject(clips = listOf(clip(ms = 10_000, fps = 30, speed = 2f)))
        // At 2x the timeline shows half as many frames, so one displayed frame spans
        // round(1000/30/2) = round(16.67) = 17ms.
        assertEquals(17L, fast.stepFrames(0L, 1))
        // Never steps past the project or before 0.
        assertEquals(0L, fast.stepFrames(0L, -1))
        val end = fast.durationMs
        assertTrue(fast.stepFrames(end, 1) <= end - 1, "cannot step past the last frame")
    }

    @Test fun locateMapsTimelineToSourceForASingleUntrimmedClip() {
        val p = VideoProject(clips = listOf(clip(ms = 8_000)))
        // With no trim and 1x speed, timeline time == source time.
        val loc = p.locate(3_210L)!!
        assertEquals(0, loc.clipIndex)
        assertEquals(3_210L, loc.offsetInClipMs)
        assertEquals(3_210L, loc.sourceMs)
    }
}
