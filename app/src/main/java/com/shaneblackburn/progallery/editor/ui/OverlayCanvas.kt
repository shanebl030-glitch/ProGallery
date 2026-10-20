package com.shaneblackburn.progallery.editor.ui

import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.edit.Overlay
import com.shaneblackburn.progallery.core.edit.Pt
import com.shaneblackburn.progallery.editor.EditRenderer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class OverlayMode { SELECT, DRAW, SHAPE }

/**
 * Shows [image] (the edited photo without overlays) with [overlays] drawn live on top,
 * and handles creating/moving/scaling/rotating them.
 */
@Composable
fun OverlayCanvas(
    image: ImageBitmap,
    overlays: List<Overlay>,
    selectedId: Long?,
    mode: OverlayMode,
    onSelect: (Long?) -> Unit,
    onGestureStart: () -> Unit,
    onUpdate: (Overlay) -> Unit,
    onStrokeStart: (Pt) -> Long,
    onStrokeAdd: (Long, Pt) -> Unit,
    onShapeStart: (Pt) -> Long,
    onShapeDrag: (Long, Pt) -> Unit,
    onDoubleTapText: (Overlay.Text) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOverlays by rememberUpdatedState(overlays)
    val currentSelected by rememberUpdatedState(selectedId)
    val currentMode by rememberUpdatedState(mode)
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val fit = fitRect(image.width, image.height, box)

        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }
                .size(with(density) { fit.width.toDp() }, with(density) { fit.height.toDp() }),
        )

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(fit) {
                    var lastTapTime = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = toNorm(down.position, fit)
                        when (currentMode) {
                            OverlayMode.DRAW -> {
                                if (!fit.contains(down.position)) return@awaitEachGesture
                                onGestureStart()
                                val id = onStrokeStart(start)
                                down.consume()
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { ch ->
                                        if (ch.positionChange() != Offset.Zero) {
                                            onStrokeAdd(id, toNorm(ch.position, fit)); ch.consume()
                                        }
                                    }
                                } while (ev.changes.any { it.pressed })
                            }
                            OverlayMode.SHAPE -> {
                                if (!fit.contains(down.position)) return@awaitEachGesture
                                onGestureStart()
                                val id = onShapeStart(start)
                                down.consume()
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.firstOrNull()?.let { ch -> onShapeDrag(id, toNorm(ch.position, fit)); ch.consume() }
                                } while (ev.changes.any { it.pressed })
                            }
                            OverlayMode.SELECT -> {
                                val hit = hitTest(currentOverlays, start, fit)
                                val now = System.currentTimeMillis()
                                if (hit != null && hit.id == currentSelected && now - lastTapTime < 300 && hit is Overlay.Text) {
                                    onDoubleTapText(hit)
                                }
                                lastTapTime = now
                                onSelect(hit?.id)
                                var target = hit ?: return@awaitEachGesture
                                onGestureStart()
                                do {
                                    val ev = awaitPointerEvent()
                                    val pan = ev.calculatePan()
                                    val zoom = ev.calculateZoom()
                                    val rot = ev.calculateRotation()
                                    if (pan != Offset.Zero || zoom != 1f || rot != 0f) {
                                        target = transform(target, pan.x / fit.width, pan.y / fit.height, zoom, rot)
                                        onUpdate(target)
                                        ev.changes.forEach { it.consume() }
                                    }
                                } while (ev.changes.any { it.pressed })
                            }
                        }
                    }
                },
        ) {
            val short = min(fit.width, fit.height)
            translate(fit.left, fit.top) {
                drawIntoNative { canvas ->
                    // Same drawing code as export, scaled to the on-screen image size.
                    val layer = canvas.saveLayer(0f, 0f, fit.width, fit.height, null)
                    overlays.filterIsInstance<Overlay.Stroke>().forEach { EditRenderer.drawStroke(canvas, it, fit.width, fit.height, short) }
                    canvas.restoreToCount(layer)
                    overlays.forEach { o ->
                        when (o) {
                            is Overlay.Shape -> EditRenderer.drawShape(canvas, o, fit.width, fit.height, short)
                            is Overlay.Text -> EditRenderer.drawText(canvas, o, fit.width, fit.height, short)
                            is Overlay.Sticker -> EditRenderer.drawSticker(canvas, o, fit.width, fit.height, short)
                            is Overlay.Stroke -> Unit
                        }
                    }
                }
                // Selection box
                overlays.firstOrNull { it.id == selectedId }?.let { sel ->
                    val b = bounds(sel, fit.width, fit.height) ?: return@let
                    withTransform({ rotate(b.rotation, Offset(b.cx, b.cy)) }) {
                        drawRect(
                            Color.White,
                            topLeft = Offset(b.cx - b.w / 2 - 8.dp.toPx(), b.cy - b.h / 2 - 8.dp.toPx()),
                            size = Size(b.w + 16.dp.toPx(), b.h + 16.dp.toPx()),
                            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIntoNative(block: (android.graphics.Canvas) -> Unit) {
    drawContext.canvas.nativeCanvas.let(block)
}

private fun toNorm(p: Offset, fit: Rect) = Pt(
    ((p.x - fit.left) / fit.width).coerceIn(0f, 1f),
    ((p.y - fit.top) / fit.height).coerceIn(0f, 1f),
)

/** Centre, size and rotation of a movable overlay in output-image pixels. */
private data class Bounds(val cx: Float, val cy: Float, val w: Float, val h: Float, val rotation: Float)

private fun bounds(o: Overlay, w: Float, h: Float): Bounds? {
    val short = min(w, h)
    return when (o) {
        is Overlay.Text -> {
            val paint = TextPaint().apply { textSize = o.size * short; typeface = EditRenderer.typeface(o.font) }
            val tw = o.text.lines().maxOfOrNull { paint.measureText(it) } ?: 0f
            val lines = max(1, o.text.lines().size)
            Bounds(o.x * w, o.y * h, min(tw, w * 0.95f), lines * paint.fontSpacing, o.rotation)
        }
        is Overlay.Sticker -> Bounds(o.x * w, o.y * h, o.size * short * 1.1f, o.size * short * 1.1f, o.rotation)
        is Overlay.Shape -> {
            val l = min(o.start.x, o.end.x) * w; val r = max(o.start.x, o.end.x) * w
            val t = min(o.start.y, o.end.y) * h; val b = max(o.start.y, o.end.y) * h
            Bounds((l + r) / 2, (t + b) / 2, max(r - l, 12f), max(b - t, 12f), 0f)
        }
        is Overlay.Stroke -> null
    }
}

/** Topmost overlay under [p] (normalised), ignoring strokes. */
private fun hitTest(overlays: List<Overlay>, p: Pt, fit: Rect): Overlay? {
    val px = p.x * fit.width
    val py = p.y * fit.height
    return overlays.asReversed().firstOrNull { o ->
        val b = bounds(o, fit.width, fit.height) ?: return@firstOrNull false
        // Rotate the point into the overlay's frame.
        val a = Math.toRadians(-b.rotation.toDouble())
        val dx = px - b.cx
        val dy = py - b.cy
        val rx = (dx * cos(a) - dy * sin(a)).toFloat()
        val ry = (dx * sin(a) + dy * cos(a)).toFloat()
        val pad = 24f
        kotlin.math.abs(rx) <= b.w / 2 + pad && kotlin.math.abs(ry) <= b.h / 2 + pad
    }
}

private fun transform(o: Overlay, dx: Float, dy: Float, zoom: Float, rot: Float): Overlay = when (o) {
    is Overlay.Text -> o.copy(
        x = (o.x + dx).coerceIn(0f, 1f), y = (o.y + dy).coerceIn(0f, 1f),
        size = (o.size * zoom).coerceIn(0.015f, 0.6f), rotation = (o.rotation + rot) % 360f,
    )
    is Overlay.Sticker -> o.copy(
        x = (o.x + dx).coerceIn(0f, 1f), y = (o.y + dy).coerceIn(0f, 1f),
        size = (o.size * zoom).coerceIn(0.03f, 1.2f), rotation = (o.rotation + rot) % 360f,
    )
    is Overlay.Shape -> {
        val cx = (o.start.x + o.end.x) / 2; val cy = (o.start.y + o.end.y) / 2
        fun scale(p: Pt) = Pt(cx + (p.x - cx) * zoom + dx, cy + (p.y - cy) * zoom + dy)
        o.copy(start = scale(o.start), end = scale(o.end))
    }
    is Overlay.Stroke -> o
}
