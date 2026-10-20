package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.edit.AdjustKey
import com.shaneblackburn.progallery.core.edit.Adjustments
import com.shaneblackburn.progallery.core.edit.CurvePoint
import com.shaneblackburn.progallery.core.edit.EditState
import com.shaneblackburn.progallery.core.edit.FilterLook
import com.shaneblackburn.progallery.core.edit.Frame
import com.shaneblackburn.progallery.core.edit.FrameMath
import com.shaneblackburn.progallery.core.edit.FrameStyle
import com.shaneblackburn.progallery.core.edit.Geometry
import com.shaneblackburn.progallery.core.edit.HslAdjust
import com.shaneblackburn.progallery.core.edit.HslValue
import com.shaneblackburn.progallery.core.edit.HueBand
import com.shaneblackburn.progallery.core.edit.Looks
import com.shaneblackburn.progallery.core.edit.NormRect
import com.shaneblackburn.progallery.core.edit.PixelPipeline
import com.shaneblackburn.progallery.core.edit.ToneCurve
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(p: Int) = (p shr 16) and 0xFF
    private fun g(p: Int) = (p shr 8) and 0xFF
    private fun b(p: Int) = p and 0xFF

    /** Smooth test image with gradients, edges and noise (deterministic). */
    private fun testImage(w: Int, h: Int): IntArray {
        val rnd = Random(42)
        return IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val edge = if ((x / 7 + y / 11) % 2 == 0) 40 else 0
            argb(
                if (x == 3) 128 else 255,
                (x * 255 / w + edge + rnd.nextInt(10)).coerceIn(0, 255),
                (y * 255 / h + rnd.nextInt(10)).coerceIn(0, 255),
                ((x + y) * 127 / (w + h) + 60 - edge / 2).coerceIn(0, 255),
            )
        }
    }

    @Test fun identityIsUntouched() {
        val img = testImage(40, 30)
        val p = PixelPipeline(EditState(), 30)
        assertTrue(p.isIdentity)
        assertContentEquals(img, p.renderFull(img, 40, 30))
    }

    @Test fun neutralNonIdentityPathIsLossless() {
        // A look at intensity 0 forces the full pipeline but must not change pixels.
        val img = testImage(32, 24)
        val p = PixelPipeline(EditState(filter = FilterLook.WARM, filterIntensity = 0f), 24)
        val out = p.renderFull(img, 32, 24)
        for (i in img.indices) {
            assertTrue(abs(r(img[i]) - r(out[i])) <= 1 && abs(g(img[i]) - g(out[i])) <= 1 && abs(b(img[i]) - b(out[i])) <= 1,
                "pixel $i changed: ${img[i].toString(16)} -> ${out[i].toString(16)}")
        }
    }

    @Test fun alphaIsPreserved() {
        val img = testImage(20, 10)
        val out = PixelPipeline(EditState(adjust = Adjustments(exposure = 0.5f, clarity = 0.5f)), 10).renderFull(img, 20, 10)
        for (i in img.indices) assertEquals(img[i] ushr 24, out[i] ushr 24)
    }

    @Test fun exposureAndBrightnessBrighten() {
        val grey = IntArray(16) { argb(255, 100, 100, 100) }
        for (key in listOf(AdjustKey.EXPOSURE, AdjustKey.BRIGHTNESS, AdjustKey.SHADOWS)) {
            val up = PixelPipeline(EditState(adjust = key.set(Adjustments(), 0.6f)), 4).renderFull(grey, 4, 4)
            val down = PixelPipeline(EditState(adjust = key.set(Adjustments(), -0.6f)), 4).renderFull(grey, 4, 4)
            assertTrue(r(up[0]) > 100, "$key up")
            assertTrue(r(down[0]) < 100, "$key down")
        }
    }

    @Test fun exposureRollsOffInsteadOfClipping() {
        val ramp = IntArray(256) { argb(255, it, it, it) }
        val out = PixelPipeline(EditState(adjust = Adjustments(exposure = 1f)), 1).renderFull(ramp, 256, 1)
        // Monotone and only the very top reaches white.
        for (i in 1 until 256) assertTrue(r(out[i]) >= r(out[i - 1]))
        assertTrue(r(out[128]) in 200..250, "mid-grey at +2 EV should be bright but not clipped: ${r(out[128])}")
    }

    @Test fun temperatureShiftsChannels() {
        val grey = IntArray(4) { argb(255, 128, 128, 128) }
        val warm = PixelPipeline(EditState(adjust = Adjustments(temperature = 0.8f)), 2).renderFull(grey, 2, 2)[0]
        assertTrue(r(warm) > b(warm))
        val cool = PixelPipeline(EditState(adjust = Adjustments(temperature = -0.8f)), 2).renderFull(grey, 2, 2)[0]
        assertTrue(b(cool) > r(cool))
    }

    @Test fun saturationAndMono() {
        val red = IntArray(4) { argb(255, 200, 60, 60) }
        val desat = PixelPipeline(EditState(adjust = Adjustments(saturation = -1f)), 2).renderFull(red, 2, 2)[0]
        assertTrue(abs(r(desat) - g(desat)) <= 1 && abs(g(desat) - b(desat)) <= 1)
        val mono = PixelPipeline(EditState(filter = FilterLook.MONO), 2).renderFull(red, 2, 2)[0]
        assertTrue(abs(r(mono) - g(mono)) <= 1 && abs(g(mono) - b(mono)) <= 1)
        val half = PixelPipeline(EditState(filter = FilterLook.MONO, filterIntensity = 0.5f), 2).renderFull(red, 2, 2)[0]
        assertTrue(r(half) > r(mono) && r(half) < 200)
    }

    @Test fun toneLutIsMonotonicForExtremeSettings() {
        val extremes = listOf(-1f, 1f)
        for (c in extremes) for (h in extremes) for (s in extremes) for (w in extremes) for (bl in extremes) {
            val lut = PixelPipeline.buildToneLut(Adjustments(contrast = c, highlights = h, shadows = s, whites = w, blacks = bl), 0.1f)
            for (i in 1 until lut.size) assertTrue(lut[i] >= lut[i - 1])
        }
    }

    @Test fun everyLookRendersWithinRange() {
        val img = testImage(24, 16)
        for (look in FilterLook.entries) {
            val out = PixelPipeline(EditState(filter = look), 16).renderFull(img, 24, 16)
            assertEquals(img.size, out.size, look.name)
        }
    }

    @Test fun lookCombineClampsToSliderRange() {
        val combined = Looks.combine(Adjustments(contrast = 0.95f), FilterLook.NOIR, 1f)
        assertEquals(1f, combined.contrast)
        assertEquals(Adjustments(contrast = 0.2f), Looks.combine(Adjustments(contrast = 0.2f), FilterLook.NONE, 1f))
    }

    /** The core guarantee: strip rendering must be identical to whole-image rendering. */
    @Test fun stripRenderingIsSeamFree() {
        val w = 97
        val h = 211
        val img = testImage(w, h)
        val state = EditState(
            adjust = Adjustments(exposure = 0.2f, clarity = 0.6f, sharpness = 0.7f, noiseReduction = 0.5f,
                blur = 0.15f, vignette = 0.5f, grain = 0.3f, contrast = 0.2f),
            filter = FilterLook.CINEMATIC,
        )
        val p = PixelPipeline(state, minOf(w, h))
        val whole = p.renderFull(img, w, h)
        for (strip in listOf(1, 7, 16, 50, 64)) {
            val inPlace = img.copyOf() // simulate in-place bitmap editing
            p.render(w, h,
                read = { y, rows, dest -> System.arraycopy(inPlace, y * w, dest, 0, rows * w) },
                write = { y, rows, src -> System.arraycopy(src, 0, inPlace, y * w, rows * w) },
                stripRows = strip,
            )
            assertContentEquals(whole, inPlace, "strip=$strip")
        }
    }

    @Test fun renderCanBeCancelled() {
        val img = testImage(64, 64)
        val p = PixelPipeline(EditState(adjust = Adjustments(exposure = 0.3f)), 64)
        var calls = 0
        val result = runCatching {
            p.render(64, 64, { y, rows, d -> System.arraycopy(img, y * 64, d, 0, rows * 64) }, { _, _, _ -> },
                stripRows = 8, isCancelled = { ++calls > 2 })
        }
        assertTrue(result.isFailure)
    }

    @Test fun hslTargetsOnlyItsBand() {
        val blue = argb(255, 40, 80, 220)
        val red = argb(255, 220, 40, 40)
        val state = EditState(hsl = HslAdjust().with(HueBand.BLUE, HslValue(saturation = -1f)))
        val out = PixelPipeline(state, 2).renderFull(intArrayOf(blue, red, blue, red), 2, 2)
        assertTrue(abs(r(out[0]) - b(out[0])) < 30, "blue desaturated")
        assertTrue(abs(r(out[1]) - r(red)) <= 2, "red untouched")
    }

    @Test fun curves() {
        assertContentEquals(IntArray(256) { it }, ToneCurve.lut(ToneCurve.IDENTITY_POINTS))
        val s = ToneCurve.lut(ToneCurve.sCurve(1f))
        assertTrue(s[64] < 64 && s[192] > 192)
        for (i in 1 until 256) assertTrue(s[i] >= s[i - 1], "monotone")
        // Messy input: unsorted, duplicates, out of range
        val messy = ToneCurve.lut(listOf(CurvePoint(0.5f, 0.6f), CurvePoint(2f, 1f), CurvePoint(0.5f, 0.55f), CurvePoint(-1f, 0f)))
        assertEquals(256, messy.size)
        val inverted = ToneCurve(master = listOf(CurvePoint(0f, 1f), CurvePoint(1f, 0f))).luts()
        assertEquals(255, inverted[0][0]); assertEquals(0, inverted[0][255])
    }

    @Test fun geometryMath() {
        val g = Geometry(crop = NormRect(0.1f, 0.2f, 0.5f, 0.9f))
        val back = g.rotateCw().rotateCw().rotateCw().rotateCw()
        assertEquals(0, back.quarterTurns)
        assertRect(g.crop, back.crop)
        assertRect(g.crop, g.flippedH().flippedH().crop)
        assertRect(g.crop, g.rotateCw().rotateCcw().crop)
        assertEquals(3000 to 4000, Geometry(quarterTurns = 1).orientedSize(4000, 3000))
        assertEquals(2000 to 1500, Geometry(crop = NormRect(0f, 0f, 0.5f, 0.5f)).outputSize(4000, 3000))
        val sq = NormRect.centredAspect(1f, 4000, 3000)
        assertEquals(0.75f, sq.width, 1e-4f)
        assertEquals(1f, sq.height, 1e-4f)
        val wide = NormRect.centredAspect(16f / 9f, 3000, 4000)
        assertEquals(1f, wide.width, 1e-4f)
        assertTrue(NormRect(-1f, -1f, 3f, 3f).clamped().isFull)
        val tiny = NormRect(0.5f, 0.5f, 0.5f, 0.5f).clamped()
        assertTrue(tiny.width > 0f && tiny.height > 0f)
    }

    @Test fun frameLayout() {
        val (size, inset) = FrameMath.layout(Frame(FrameStyle.SOLID, 0.05f), 4000, 3000)
        assertEquals(4300 to 3300, size)
        assertContentEquals(intArrayOf(150, 150, 4000, 3000), inset)
        val (sq, sqInset) = FrameMath.layout(Frame(FrameStyle.SQUARE_FIT, 0f), 4000, 3000)
        assertEquals(4000 to 4000, sq)
        assertEquals(500, sqInset[1])
    }

    @Test fun adjustKeysRoundTrip() {
        for (k in AdjustKey.entries) {
            val a = k.set(Adjustments(), 0.37f)
            assertEquals(0.37f, k.get(a), k.name)
            assertEquals(k.max, k.get(k.set(Adjustments(), 99f)))
        }
    }

    private fun assertRect(a: NormRect, b: NormRect) {
        assertEquals(a.left, b.left, 1e-5f); assertEquals(a.top, b.top, 1e-5f)
        assertEquals(a.right, b.right, 1e-5f); assertEquals(a.bottom, b.bottom, 1e-5f)
    }
}
