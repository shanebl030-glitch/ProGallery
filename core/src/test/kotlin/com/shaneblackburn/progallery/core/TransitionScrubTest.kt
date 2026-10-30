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

    // ---- Multiple transitions at separate timeline positions (v0.4.25 fix) ----

    // Three 4 s clips: Ripple between clips 1&2 (cut at 4000 ms), Blur between 2&3 (cut at 8000 ms),
    // each transition 1000 ms (500 ms on each side of its cut). The middle clip is clip index 1.
    private fun twoTransitionProject() = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000), clip(3, 4_000)))
        .setTransition(0, Transition(TransitionType.RIPPLE, 1_000))
        .setTransition(1, Transition(TransitionType.BLUR, 1_000))

    @Test fun eachTransitionStaysInItsOwnWindowAndTheMiddleIsNormalFootage() {
        val p = twoTransitionProject()
        // THE regression: scrubbing the middle clip between the two cuts shows NO transition.
        for (t in 4_600L..7_400L step 100L) {
            assertTrue(uniformsAt(p, t).isIdentity, "middle of the clip at t=$t must be plain footage (no transition)")
        }
        // Ripple only around the first cut (4000), blur only around the second (8000).
        for (t in listOf(3_900L, 4_000L, 4_100L)) {
            val u = uniformsAt(p, t)
            assertTrue(u.ripple > 0f && u.blur == 0f, "t=$t is inside the Ripple window only")
        }
        for (t in listOf(7_900L, 8_000L, 8_100L)) {
            val u = uniformsAt(p, t)
            assertTrue(u.blur > 0f && u.ripple == 0f, "t=$t is inside the Blur window only")
        }
        // Well outside either window — before the first cut and after the second — is plain footage.
        for (t in (0L..3_300L step 300L) + (8_700L..11_900L step 300L)) {
            assertTrue(uniformsAt(p, t).isIdentity, "t=$t is outside both transitions and must be plain footage")
        }
    }

    @Test fun neitherTransitionEverLeaksOutsideItsDuration() {
        val p = twoTransitionProject()
        for (t in 0L..11_990L step 10L) {
            val u = uniformsAt(p, t)
            if (u.ripple > 0f) assertTrue(t in 3_500L..4_500L, "Ripple appeared at t=$t, outside its window")
            if (u.blur > 0f) assertTrue(t in 7_500L..8_500L, "Blur appeared at t=$t, outside its window")
            // The two transitions never render at the same time.
            assertTrue(!(u.ripple > 0f && u.blur > 0f), "Ripple and Blur overlapped at t=$t")
        }
    }

    @Test fun forwardAndBackwardScrubAcrossBothTransitionsAgree() {
        val p = twoTransitionProject()
        val positions = (0L..11_990L step 70L).toList()
        val forward = positions.map { uniformsAt(p, it) }
        val backward = positions.reversed().map { uniformsAt(p, it) }.reversed()
        assertEquals(forward, backward, "every position renders identically whichever way it is scrubbed")
    }

    @Test fun jumpingDirectlyBetweenPositionsIsConsistent() {
        val p = twoTransitionProject()
        // Jump before → between → after → back, in a non-monotonic order (random-access, like tapping
        // the timeline). Each position must evaluate the same as computing it in isolation.
        val before = uniformsAt(p, 2_000); val rip = uniformsAt(p, 4_000)
        val between = uniformsAt(p, 6_000); val blur = uniformsAt(p, 8_000)
        val after = uniformsAt(p, 10_000)
        for ((t, expected) in listOf(10_000L to after, 4_000L to rip, 2_000L to before, 8_000L to blur, 6_000L to between,
                                     6_000L to between, 4_000L to rip)) {
            assertEquals(expected, uniformsAt(p, t), "jump to t=$t must match its isolated value")
        }
        assertTrue(before.isIdentity && between.isIdentity && after.isIdentity)
        assertTrue(rip.ripple > 0f && blur.blur > 0f)
    }

    // ---- Which paused/scrub frames use the composed CPU preview ----

    @Test fun composedPreviewUsedOnlyForTransitionBearingVideoClips() {
        val p = twoTransitionProject()
        // All three clips carry a transition on at least one edge → composed preview throughout.
        for (t in listOf(1_000L, 4_000L, 6_000L, 8_000L, 10_000L)) {
            assertTrue(p.usesComposedTransitionPreview(t), "t=$t is on a transition-bearing clip")
        }
        // A project with no transitions keeps the full-resolution GPU preview everywhere.
        val plain = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000)))
        for (t in listOf(0L, 2_000L, 5_000L, 7_000L)) {
            assertTrue(!plain.usesComposedTransitionPreview(t), "no transition at t=$t → GPU full-res preview")
        }
    }

    @Test fun transitionFreeMiddleClipKeepsGpuPreview() {
        // Five clips; transitions only at the far ends. The middle clip (index 2) touches no transition.
        val p = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000), clip(3, 4_000), clip(4, 4_000), clip(5, 4_000)))
            .setTransition(0, Transition(TransitionType.RIPPLE, 1_000))
            .setTransition(3, Transition(TransitionType.BLUR, 1_000))
        // Clip index 2 spans timeline 8000..11999.
        for (t in 8_200L..11_800L step 200L) {
            assertTrue(!p.usesComposedTransitionPreview(t), "t=$t is on the transition-free middle clip → GPU preview")
            assertTrue(uniformsAt(p, t).isIdentity, "and it is plain footage")
        }
        // The end clips still use the composed preview.
        assertTrue(p.usesComposedTransitionPreview(1_000L) && p.usesComposedTransitionPreview(19_000L))
    }

    // ---- GPU live-playback timing (v0.4.26 fix) ----
    // The GPU effect computes clip-relative transition time as (presentationTimeUs/1000 − clipStartMs),
    // where presentationTimeUs is the composition's global OUTPUT time and clipStartMs is the sum of
    // earlier clips' output durations. These tests prove that value equals the true clip-relative output
    // time the (already-correct) position path uses, and that the old wrong anchor is what caused the
    // incoming transition to flood a clip.

    private fun clipStartMs(p: VideoProject, idx: Int): Long = p.clips.take(idx).sumOf { it.timelineDurationMs }

    @Test fun clipStartPlusGlobalTimeReconstructsTrueClipTimeWithTrimAndSpeed() {
        // Clip 1 is trimmed AND sped up (2×), so its output duration differs from its source span —
        // exactly the case that must still map correctly (Shane's clip was trimmed; speed is the harder one).
        val c1 = clip(1, 12_000).withTrim(2_000, 10_000).copy(speed = 2f) // trimmed 8s @2× → 4000 ms output
        val p = VideoProject(listOf(c1, clip(2, 6_000), clip(3, 6_000)))
            .setTransition(0, Transition(TransitionType.RIPPLE, 1_000))
            .setTransition(1, Transition(TransitionType.BLUR, 1_000))
        assertEquals(4_000L, c1.timelineDurationMs, "trim+speed output duration")
        // For global output positions inside each clip, the fix's time = globalPos − clipStartMs must
        // equal framePreviewSpec().inClipTimelineMs (the true clip-relative output time).
        for (globalPos in 0L..15_990L step 30L) {
            val spec = p.framePreviewSpec(globalPos)
            val fixTime = globalPos - clipStartMs(p, spec.clipIndex)
            // Interior of a clip only (the spec clamps the last ms of each clip); allow the clamp at edges.
            val clipDur = p.clips[spec.clipIndex].timelineDurationMs
            val interior = globalPos - clipStartMs(p, spec.clipIndex)
            if (interior in 0 until clipDur) {
                assertEquals(spec.inClipTimelineMs, fixTime,
                    "at global $globalPos the fix's clip time must equal the true clip-relative time")
            }
        }
    }

    @Test fun wrongAnchorFloodsIncomingTransitionButTrueTimeDoesNot() {
        // Reproduces the mechanism from the on-device capture: clip 2 is 14100 ms with incoming Ripple.
        val edge = VideoProject(listOf(clip(1, 29_267), clip(2, 14_100), clip(3, 6_000)))
            .setTransition(0, Transition(TransitionType.RIPPLE, 1_400))
            .setTransition(1, Transition(TransitionType.BLUR, 1_400))
            .clipEdge(1)
        // Old code (firstUs anchored near the clip END) produced a NEGATIVE clip time → full incoming ripple.
        val wrong = edge.sampleAt(-6_633)
        assertTrue(wrong.type == TransitionType.RIPPLE && wrong.incoming && wrong.edge == 1f,
            "a negative/mis-anchored time clamps the incoming Ripple to full — the reported bug")
        // The fix's true clip-relative time (mid-clip) is plain footage.
        assertTrue(edge.uniformsAt(7_333).isIdentity, "the true mid-clip time shows no transition")
        // And the incoming Ripple is confined to the clip's head, the outgoing Blur to its tail.
        assertTrue(edge.sampleAt(100).type == TransitionType.RIPPLE && edge.sampleAt(100).incoming)
        assertTrue(edge.sampleAt(14_000).type == TransitionType.BLUR && !edge.sampleAt(14_000).incoming)
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
