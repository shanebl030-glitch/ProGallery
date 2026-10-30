package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionCpu
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.TransitionUniforms
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fix 1 regression — the composed-preview transition must be a pure, deterministic function of the
 * PLAYHEAD POSITION, evaluated afresh every render. The old backwards-scrub bug came from a cached
 * frame freezing the transition; these tests lock in the property the renderer now relies on:
 * scrubbing forwards and backwards over a transition window yields identical uniforms at identical
 * positions, the value genuinely varies across the window (never stuck), and different positions
 * render visibly different frames. The maths here is the same [TransitionCpu] the export runs.
 */
class TransitionScrubTest {

    private fun clip(id: Long, dur: Long) = Clip(id, "content://v/$id", "v$id.mp4", dur, 1920, 1080)

    private fun rippleProject() = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000)))
        .setTransition(0, Transition(TransitionType.RIPPLE, 1_000))

    /** The uniforms the composed preview would use at whole-timeline [t] on clip 0's outgoing edge. */
    private fun uniformsAt(p: VideoProject, t: Long): TransitionUniforms {
        val spec = p.framePreviewSpec(t)
        return p.clipEdge(spec.clipIndex).uniformsAt(spec.inClipTimelineMs)
    }

    // Clip 0 outgoing ripple window is the 500 ms before the 4000 ms cut (half of 1000 ms).
    private val windowPositions = (3_500L..3_950L step 50L).toList()

    @Test fun rippleIsDeterministicForwardsAndBackwards() {
        val p = rippleProject()
        val forward = windowPositions.map { uniformsAt(p, it) }
        val backward = windowPositions.reversed().map { uniformsAt(p, it) }.reversed()
        assertEquals(forward, backward,
            "the same scrub position must produce identical uniforms whichever way it was reached")
    }

    @Test fun rippleVariesAcrossTheWindowAndIsNotFrozen() {
        val p = rippleProject()
        val ripples = windowPositions.map { uniformsAt(p, it).ripple }
        assertEquals(0f, ripples.first(), 1e-4f, "no ripple at the window's outer edge")
        assertTrue(ripples.last() > 0f, "ripple is active approaching the cut")
        assertTrue(ripples.first() < ripples.last(), "ripple strengthens toward the cut (not a frozen value)")
        assertTrue(ripples.zipWithNext().all { (a, b) -> b >= a }, "ripple progresses monotonically, never stuck")
        assertTrue(ripples.toSet().size > 1, "more than one distinct value across the sweep")
    }

    @Test fun differentPositionsRenderVisiblyDifferentFrames() {
        val p = rippleProject()
        val far = uniformsAt(p, 3_600)
        val near = uniformsAt(p, 3_940)
        assertTrue(meanAbsDiff(far, near) > 1e-4f,
            "a cached frame reused across positions would show identical pixels; re-rendering must not")
    }

    @Test fun otherTransitionsAlsoVaryPerPositionNotFrozen() {
        // The transitions flagged for extra checking: each must change across the scrub window so the
        // preview cannot freeze on any of them.
        for (type in listOf(
            TransitionType.SPIN, TransitionType.ZOOM, TransitionType.SHAKE,
            TransitionType.GLITCH, TransitionType.PIXELATE,
        )) {
            val p = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000)))
                .setTransition(0, Transition(type, 1_000))
            val far = uniformsAt(p, 3_600)
            val near = uniformsAt(p, 3_940)
            assertTrue(far != near, "$type uniforms must differ between scrub positions (not frozen)")
            assertTrue(meanAbsDiff(far, near) > 1e-4f, "$type renders differently between scrub positions")
        }
    }

    // ---- render helpers (same approach as TransitionCpuTest) ----

    private val w = 48
    private val h = 48

    private fun source(u: Float, v: Float): FloatArray {
        val checker = if (((u * 16).toInt() + (v * 16).toInt()) % 2 == 0) 1f else 0f
        return floatArrayOf(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), checker)
    }

    private fun meanAbsDiff(a: TransitionUniforms, b: TransitionUniforms): Float {
        var sum = 0f
        for (yy in 0 until h) for (xx in 0 until w) {
            val x = (xx + 0.5f) / w; val y = (yy + 0.5f) / h
            val ca = TransitionCpu.render(a, x, y, 0f, ::source)
            val cb = TransitionCpu.render(b, x, y, 0f, ::source)
            sum += abs(ca[0] - cb[0]) + abs(ca[1] - cb[1]) + abs(ca[2] - cb[2])
        }
        return sum / (w * h * 3)
    }
}
