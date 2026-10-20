package com.shaneblackburn.progallery.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.edit.NormRect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Rectangle where an image of [imgW]×[imgH] is drawn when fitted into [box]. */
fun fitRect(imgW: Int, imgH: Int, box: Size): Rect {
    if (imgW <= 0 || imgH <= 0 || box.width <= 0f || box.height <= 0f) return Rect.Zero
    val scale = min(box.width / imgW, box.height / imgH)
    val w = imgW * scale
    val h = imgH * scale
    val left = (box.width - w) / 2f
    val top = (box.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

private enum class Handle { MOVE, L, T, R, B, TL, TR, BL, BR }

/**
 * Interactive crop box over the full (uncropped) image.
 *
 * @param aspect locked pixel aspect ratio (w/h) or null for free crop.
 */
@Composable
fun CropOverlay(
    image: ImageBitmap,
    crop: NormRect,
    aspect: Float?,
    onCropChange: (NormRect) -> Unit,
    onGestureStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCrop by rememberUpdatedState(crop)
    val currentAspect by rememberUpdatedState(aspect)
    var active by remember { mutableStateOf<Handle?>(null) }
    val touchSlop = with(LocalDensity.current) { 28.dp.toPx() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        // Leave a margin so corner handles are reachable.
        val margin = with(LocalDensity.current) { 24.dp.toPx() }
        val inner = Size(box.width - 2 * margin, box.height - 2 * margin)
        val fit = fitRect(image.width, image.height, inner).translate(Offset(margin, margin))
        val imgRatio = image.width.toFloat() / image.height

        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }
                .size(with(LocalDensity.current) { fit.width.toDp() }, with(LocalDensity.current) { fit.height.toDp() }),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(fit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = toScreen(currentCrop, fit)
                            active = hitHandle(pos, r, touchSlop)
                            if (active != null) onGestureStart()
                        },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, drag ->
                        val h = active ?: return@detectDragGestures
                        change.consume()
                        val dx = drag.x / fit.width
                        val dy = drag.y / fit.height
                        onCropChange(adjust(currentCrop, h, dx, dy, currentAspect?.let { it / imgRatio }))
                    }
                },
        ) {
            val r = toScreen(crop, fit)
            // Dim outside the crop.
            val hole = Path().apply { addRect(r) }
            clipPath(hole, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
            // Rule-of-thirds grid while dragging.
            if (active != null) {
                for (i in 1..2) {
                    val x = r.left + r.width * i / 3f
                    val y = r.top + r.height * i / 3f
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
                }
            }
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(1.5.dp.toPx()))
            // Corner handles
            val len = 18.dp.toPx()
            val w = 4.dp.toPx()
            listOf(
                r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f),
                r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f),
            ).forEach { (p, d) ->
                drawLine(Color.White, p, p + Offset(len * d.x, 0f), w)
                drawLine(Color.White, p, p + Offset(0f, len * d.y), w)
            }
        }
    }
}

private fun toScreen(c: NormRect, fit: Rect) = Rect(
    fit.left + c.left * fit.width, fit.top + c.top * fit.height,
    fit.left + c.right * fit.width, fit.top + c.bottom * fit.height,
)

private fun hitHandle(p: Offset, r: Rect, slop: Float): Handle? {
    val nearL = abs(p.x - r.left) < slop
    val nearR = abs(p.x - r.right) < slop
    val nearT = abs(p.y - r.top) < slop
    val nearB = abs(p.y - r.bottom) < slop
    val inX = p.x > r.left - slop && p.x < r.right + slop
    val inY = p.y > r.top - slop && p.y < r.bottom + slop
    return when {
        nearL && nearT -> Handle.TL
        nearR && nearT -> Handle.TR
        nearL && nearB -> Handle.BL
        nearR && nearB -> Handle.BR
        nearL && inY -> Handle.L
        nearR && inY -> Handle.R
        nearT && inX -> Handle.T
        nearB && inX -> Handle.B
        r.contains(p) -> Handle.MOVE
        else -> null
    }
}

/**
 * Applies a drag to the crop rect. [normAspect] is the locked aspect in normalised
 * units (pixel aspect ÷ image aspect), or null for free.
 */
private fun adjust(c: NormRect, h: Handle, dx: Float, dy: Float, normAspect: Float?): NormRect {
    val min = 0.05f
    if (h == Handle.MOVE) {
        val ndx = dx.coerceIn(-c.left, 1f - c.right)
        val ndy = dy.coerceIn(-c.top, 1f - c.bottom)
        return NormRect(c.left + ndx, c.top + ndy, c.right + ndx, c.bottom + ndy)
    }
    var l = c.left; var t = c.top; var r = c.right; var b = c.bottom
    when (h) {
        Handle.L, Handle.TL, Handle.BL -> l = (l + dx).coerceIn(0f, r - min)
        Handle.R, Handle.TR, Handle.BR -> r = (r + dx).coerceIn(l + min, 1f)
        else -> Unit
    }
    when (h) {
        Handle.T, Handle.TL, Handle.TR -> t = (t + dy).coerceIn(0f, b - min)
        Handle.B, Handle.BL, Handle.BR -> b = (b + dy).coerceIn(t + min, 1f)
        else -> Unit
    }
    if (normAspect != null) {
        // Keep the aspect by deriving height from width (or width from height for T/B).
        val horizontalDriven = h != Handle.T && h != Handle.B
        if (horizontalDriven) {
            var newH = (r - l) / normAspect
            val anchorTop = h == Handle.BL || h == Handle.BR || h == Handle.R || h == Handle.L
            if (anchorTop) {
                if (t + newH > 1f) { newH = 1f - t; val w = newH * normAspect; if (h == Handle.L || h == Handle.BL) l = r - w else r = l + w }
                b = t + newH
            } else {
                if (b - newH < 0f) { newH = b; val w = newH * normAspect; if (h == Handle.TL) l = r - w else r = l + w }
                t = b - newH
            }
        } else {
            var newW = (b - t) * normAspect
            if (l + newW > 1f) { newW = 1f - l; val hh = newW / normAspect; if (h == Handle.T) t = b - hh else b = t + hh }
            r = l + newW
        }
    }
    return NormRect(l, t, r, b).clamped(min)
}
