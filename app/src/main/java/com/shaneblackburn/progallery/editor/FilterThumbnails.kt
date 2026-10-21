package com.shaneblackburn.progallery.editor

import android.graphics.Bitmap
import com.shaneblackburn.progallery.core.edit.EditState
import com.shaneblackburn.progallery.core.edit.FilterLook
import com.shaneblackburn.progallery.core.edit.PixelPipeline
import kotlin.math.roundToInt

/** Small previews of every filter look for the editor's Filters strip. */
object FilterThumbnails {
    fun render(src: Bitmap, side: Int = 160): Map<FilterLook, Bitmap> {
        val scale = side.toFloat() / maxOf(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return FilterLook.entries.associateWith { look ->
            val out = PixelPipeline(EditState(filter = look), minOf(w, h)).renderFull(px, w, h)
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
