package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.video.TransitionCpu
import com.shaneblackburn.progallery.core.video.TransitionDirection
import com.shaneblackburn.progallery.core.video.TransitionSample
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.TransitionUniforms
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the transition maths by RENDERING frames with the CPU reference ([TransitionCpu],
 * which mirrors the GPU shader) and checking the pixels actually change the way each transition
 * should. This proves the design on the JVM; the on-device export of the same maths is checked
 * separately by the instrumented tests.
 */
class TransitionCpuTest {
    private val w = 64
    private val h = 64

    /** A detailed test image: horizontal + vertical gradients plus a fine checker (high detail). */
    private fun source(u: Float, v: Float): FloatArray {
        val checker = if (((u * 16).toInt() + (v * 16).toInt()) % 2 == 0) 1f else 0f
        return floatArrayOf(u.coerceIn(0f, 1f), v.coerceIn(0f, 1f), checker)
    }

    private fun sample(type: TransitionType, edge: Float, incoming: Boolean = false,
                       dir: TransitionDirection = TransitionDirection.AUTO, intensity: Float = 0.6f) =
        TransitionSample(type, edge, incoming, dir, intensity).uniforms()

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

    private fun luma(u: TransitionUniforms) = TransitionCpu.meanLuma(w, h, u, ::source)
    private fun detail(u: TransitionUniforms) = TransitionCpu.highFrequency(w, h, u, ::source)

    @Test fun identityAtEdgeZeroForEverySelectableType() {
        val id = TransitionUniforms.IDENTITY
        for (type in TransitionType.selectable) {
            if (type == TransitionType.NONE) continue
            val u = sample(type, 0f)
            assertTrue(u.isIdentity, "$type at edge 0 must be a no-op")
            assertEquals(0f, meanAbsDiff(u, id), 1e-4f, "$type renders identically to the source at edge 0")
        }
    }

    @Test fun fadeReachesBlackAndWhite() {
        assertTrue(luma(sample(TransitionType.FADE_BLACK, 1f)) < 0.02f, "fade-black is black at the cut")
        assertTrue(luma(sample(TransitionType.FADE_WHITE, 1f)) > 0.98f, "fade-white is white at the cut")
        // Half-way is a partial blend, clearly between the source and the target.
        val base = luma(TransitionUniforms.IDENTITY)
        assertTrue(luma(sample(TransitionType.FADE_BLACK, 0.5f)) < base, "half fade-black is darker than source")
    }

    @Test fun blurSoftensDetail() {
        val sharp = detail(TransitionUniforms.IDENTITY)
        val blurred = detail(sample(TransitionType.BLUR, 1f))
        assertTrue(blurred < sharp * 0.75f, "blur reduces detail at the cut ($sharp -> $blurred)")
        // Higher intensity = a larger blur radius (the shader/CPU contract).
        val soft = sample(TransitionType.BLUR, 1f, intensity = 0.2f).blur
        val hard = sample(TransitionType.BLUR, 1f, intensity = 1f).blur
        assertTrue(hard > soft, "more intensity = a bigger blur radius ($soft -> $hard)")
    }

    @Test fun zoomInMagnifiesZoomOutRevealsBlack() {
        val inU = sample(TransitionType.ZOOM, 1f, dir = TransitionDirection.IN)
        val outU = sample(TransitionType.ZOOM, 1f, dir = TransitionDirection.OUT)
        assertTrue(inU.scale > 1f, "zoom-in punches in")
        assertTrue(outU.scale < 1f, "zoom-out pulls back")
        // Pulling back puts black around the shrunken frame, so mean luma drops.
        assertTrue(luma(outU) < luma(TransitionUniforms.IDENTITY), "zoom-out reveals black border")
        // Both clearly change the frame.
        assertTrue(meanAbsDiff(inU, TransitionUniforms.IDENTITY) > 0.02f)
    }

    @Test fun crossZoomBlursAndFlashes() {
        val u = sample(TransitionType.CROSS_ZOOM, 1f)
        assertTrue(u.scale > 1f && u.blur > 0f, "cross zoom combines punch-in and blur")
        assertTrue(detail(u) < detail(TransitionUniforms.IDENTITY), "cross zoom softens the frame")
    }

    @Test fun whipAndPushMoveTheFrame() {
        val whip = sample(TransitionType.WHIP_PAN, 1f, incoming = false)
        val push = sample(TransitionType.PUSH, 1f, incoming = false)
        assertTrue(abs(whip.offX) > 0f || abs(whip.offY) > 0f, "whip pan translates")
        assertTrue(abs(push.offX) > 0f || abs(push.offY) > 0f, "push translates")
        assertTrue(meanAbsDiff(whip, TransitionUniforms.IDENTITY) > 0.05f, "whip clearly changes the frame")
        // Incoming and outgoing move in opposite directions (they meet at the cut).
        val pushIn = sample(TransitionType.PUSH, 1f, incoming = true, dir = TransitionDirection.LEFT)
        val pushOut = sample(TransitionType.PUSH, 1f, incoming = false, dir = TransitionDirection.LEFT)
        assertTrue(pushIn.offX * pushOut.offX < 0f, "push head and tail slide opposite ways")
    }

    @Test fun wipeAndIrisCoverToBlack() {
        assertTrue(luma(sample(TransitionType.WIPE, 1f)) < 0.2f, "wipe covers the frame at the cut")
        assertTrue(luma(sample(TransitionType.IRIS, 1f)) < 0.2f, "iris closes to black at the cut")
        // A half wipe covers part of the frame (between source and fully black).
        val half = luma(sample(TransitionType.WIPE, 0.5f))
        assertTrue(half < luma(TransitionUniforms.IDENTITY) && half > 0.05f, "half wipe is partially covered")
        // Left and right wipes sweep from opposite sides.
        val left = sample(TransitionType.WIPE, 0.5f, dir = TransitionDirection.LEFT)
        val right = sample(TransitionType.WIPE, 0.5f, dir = TransitionDirection.RIGHT)
        assertTrue(meanAbsDiff(left, right) > 0.05f, "wipe direction changes which side is covered")
    }

    @Test fun lightLeakBrightens() {
        val u = sample(TransitionType.LIGHT_LEAK, 1f)
        assertTrue(luma(u) > luma(TransitionUniforms.IDENTITY), "light leak adds a warm glow")
    }

    @Test fun glitchDisplacesPixels() {
        val u = sample(TransitionType.GLITCH, 1f)
        assertTrue(u.glitch > 0f)
        assertTrue(meanAbsDiff(u, TransitionUniforms.IDENTITY) > 0.01f, "glitch disturbs the frame")
    }

    @Test fun effectGrowsWithEdge() {
        // A fade gets steadily darker from edge 0 to edge 1 (no sudden jumps/black frames early).
        var prev = luma(sample(TransitionType.FADE_BLACK, 0f))
        var e = 0.1f
        while (e <= 1f) {
            val cur = luma(sample(TransitionType.FADE_BLACK, e))
            assertTrue(cur <= prev + 1e-3f, "fade is monotonic (edge $e)")
            prev = cur; e += 0.1f
        }
    }

    @Test fun spinPixelateShakeRippleTransform() {
        val id = TransitionUniforms.IDENTITY
        // Spin rotates and zooms a little so the corners don't show black.
        val spin = sample(TransitionType.SPIN, 1f)
        assertTrue(spin.rotate != 0f && spin.scale > 1f, "spin rotates + zooms")
        assertTrue(meanAbsDiff(spin, id) > 0.03f, "spin changes the frame")
        // Spin direction flips the rotation sign.
        assertTrue(
            sample(TransitionType.SPIN, 1f, dir = TransitionDirection.LEFT).rotate *
                sample(TransitionType.SPIN, 1f, dir = TransitionDirection.RIGHT).rotate < 0f,
            "left/right spin opposite ways",
        )
        // Pixelate quantises into blocks.
        val pix = sample(TransitionType.PIXELATE, 1f)
        assertTrue(pix.pixel > 0f)
        assertTrue(meanAbsDiff(pix, id) > 0.005f, "pixelate changes the frame")
        // Shake jitters the frame.
        val shake = sample(TransitionType.SHAKE, 1f)
        assertTrue(shake.shake > 0f)
        assertTrue(meanAbsDiff(shake, id) > 0.01f, "shake displaces the frame")
        // Ripple warps the frame.
        val rip = sample(TransitionType.RIPPLE, 1f)
        assertTrue(rip.ripple > 0f)
        assertTrue(meanAbsDiff(rip, id) > 0.005f, "ripple warps the frame")
        // All are a no-op at the endpoints.
        for (t in listOf(TransitionType.SPIN, TransitionType.PIXELATE, TransitionType.SHAKE, TransitionType.RIPPLE)) {
            assertTrue(sample(t, 0f).isIdentity, "$t is identity at edge 0")
        }
    }

    @Test fun crossDissolveIsNotRenderablePerClip() {
        // The per-clip pipeline must treat the cross-clip dissolve as inactive (never a black frame).
        val s = TransitionSample(TransitionType.CROSS_DISSOLVE, 1f, incoming = false)
        assertTrue(!s.isActive)
        assertTrue(s.uniforms().isIdentity, "cross dissolve falls through to identity per-clip")
        assertTrue(TransitionType.CROSS_DISSOLVE !in TransitionType.selectable)
    }
}
