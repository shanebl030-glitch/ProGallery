package com.shaneblackburn.progallery.ui.viewer

import android.graphics.Bitmap
import android.graphics.Matrix
import coil.size.Size
import coil.transform.Transformation

/**
 * View-only rotation for the full-screen viewer. Rotating the decoded bitmap (rather
 * than the composable) keeps zoom and pan gestures aligned with what the user sees.
 * The file on disk is never changed.
 */
class RotateTransformation(private val degrees: Int) : Transformation {
    override val cacheKey: String = "rotate:$degrees"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (degrees % 360 == 0) return input
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(input, 0, 0, input.width, input.height, m, true)
    }
}
