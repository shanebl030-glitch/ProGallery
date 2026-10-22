package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.video.Compat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM tests for the version/size compatibility decisions, exercised across simulated Android
 * versions and source sizes. These verify the LOGIC on the JVM — they are not a substitute for
 * running on a physical device.
 */
class CompatTest {

    @Test fun supportedAcrossAndroidVersions() {
        assertTrue(!Compat.isSupported(28), "Android 9 (API 28) is below minSdk")
        assertTrue(Compat.isSupported(29), "Android 10 supported")
        // Android 13 and every newer version must be supported and meet the required floor.
        for (sdk in listOf(33, 34, 35, 36, 40)) {
            assertTrue(Compat.isSupported(sdk), "API $sdk supported")
            assertTrue(Compat.meetsRequiredFloor(sdk), "API $sdk meets the Android 13+ floor")
        }
        // 10–12 are supported too, just below the stated 13 floor.
        assertTrue(Compat.isSupported(32) && !Compat.meetsRequiredFloor(32))
        assertEquals(29, Compat.MIN_SDK)
    }

    @Test fun hdrToneMapAvailableFromApi29() {
        assertTrue(!Compat.hdrToneMapSupported(28))
        assertTrue(Compat.hdrToneMapSupported(29))
        assertTrue(Compat.hdrToneMapSupported(35))
    }

    @Test fun clampOutputStaysEvenWithinGpuLimit() {
        // Common sizes pass through unchanged (already even, within limit).
        assertEquals(3840 to 2160, Compat.clampOutput(3840, 2160), "4K kept")
        assertEquals(1920 to 1080, Compat.clampOutput(1920, 1080))
        assertEquals(2176 to 1812, Compat.clampOutput(2176, 1812), "foldable-ish square kept")
        // Odd dimensions are made even (encoders require it).
        assertEquals(1280 to 720, Compat.clampOutput(1281, 721))
        // Absurdly large sources clamp to within the GPU-safe max, keeping aspect + even dims.
        val (w, h) = Compat.clampOutput(15360, 8640) // 16K
        assertTrue(maxOf(w, h) <= Compat.MAX_OUTPUT_DIMEN, "clamped to <= ${Compat.MAX_OUTPUT_DIMEN}")
        assertTrue(w % 2 == 0 && h % 2 == 0, "even dims")
        assertEquals(15360.0 / 8640.0, w.toDouble() / h, 0.02, "aspect preserved")
        // Never upscales; floors at 2.
        assertEquals(2 to 2, Compat.clampOutput(1, 1))
        assertEquals(640 to 480, Compat.clampOutput(640, 480))
    }
}
