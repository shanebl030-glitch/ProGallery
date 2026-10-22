package com.shaneblackburn.progallery.core.video

import kotlin.math.roundToInt

/**
 * Device / Android-version compatibility decisions, written as pure functions so they can be
 * unit-tested across Android versions and source/screen sizes on the JVM — without a device or
 * emulator. The app layer supplies the real values (Build.VERSION.SDK_INT, decoded sizes) and
 * these functions decide behaviour, so the logic is verified even when on-device CI is unavailable.
 *
 * Supported range: minSdk = Android 10 (API 29), which covers Android 13+ (the required floor)
 * and older devices too; targetSdk tracks the latest stable Android. We keep minSdk at 29 rather
 * than raising it to 33, because 29 already satisfies "Android 13 and newer" while also supporting
 * 10–12 — raising it would only drop devices with no benefit for a personal, non-Play app.
 */
object Compat {
    /** Minimum Android the app installs on (must match app/build.gradle minSdk). */
    const val MIN_SDK = 29

    /** API levels for the Android versions Shane called out. */
    const val ANDROID_10 = 29
    const val ANDROID_13 = 33
    const val ANDROID_14 = 34
    const val ANDROID_15 = 35

    /** True if the app supports this Android version. */
    fun isSupported(sdkInt: Int): Boolean = sdkInt >= MIN_SDK

    /** True if this version meets Shane's required Android 13+ floor. */
    fun meetsRequiredFloor(sdkInt: Int): Boolean = sdkInt >= ANDROID_13

    /**
     * OpenGL HDR→SDR tone-mapping (Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) is
     * available from API 29 and is a pass-through for SDR content, so it is always safe to request
     * on supported devices. Centralised here so the decision is explicit and tested.
     */
    fun hdrToneMapSupported(sdkInt: Int): Boolean = sdkInt >= ANDROID_10

    /**
     * A conservative maximum output dimension that stays within the OpenGL ES texture limit on
     * every supported GPU (GL_MAX_TEXTURE_SIZE is at least 4096 on all GLES3 devices; Adreno/Mali
     * flagships like the Z Fold5's Adreno 740 are 16384). Clamping protects the GL pipeline from
     * absurdly large sources; normal phone video (up to 8K) is unaffected.
     */
    const val MAX_OUTPUT_DIMEN = 7680

    /**
     * Clamps an output size to even dimensions (encoders require even) within [maxDimen],
     * preserving aspect ratio and never upscaling.
     */
    fun clampOutput(width: Int, height: Int, maxDimen: Int = MAX_OUTPUT_DIMEN): Pair<Int, Int> {
        var w = width.coerceAtLeast(2)
        var h = height.coerceAtLeast(2)
        val longest = maxOf(w, h)
        if (longest > maxDimen) {
            val s = maxDimen.toDouble() / longest
            w = (w * s).roundToInt()
            h = (h * s).roundToInt()
        }
        return (w / 2 * 2).coerceAtLeast(2) to (h / 2 * 2).coerceAtLeast(2)
    }
}
