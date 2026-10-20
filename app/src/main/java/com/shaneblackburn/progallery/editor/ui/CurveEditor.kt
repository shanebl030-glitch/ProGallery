package com.shaneblackburn.progallery.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.shaneblackburn.progallery.core.edit.CurvePoint
import com.shaneblackburn.progallery.core.edit.ToneCurve

/**
 * Interactive tone curve: drag points, tap empty space to add a point, double-tap a
 * point to remove it (end points can be moved but not removed).
 */
@Composable
fun CurveEditor(
    points: List<CurvePoint>,
    color: Color,
    gridColor: Color,
    onGestureStart: () -> Unit,
    onChange: (List<CurvePoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(points)
    var dragIndex by remember { mutableIntStateOf(-1) }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { pos ->
                        val idx = nearest(current, pos, size.width.toFloat(), size.height.toFloat(), 28.dp.toPx())
                        if (idx > 0 && idx < current.lastIndex) {
                            onGestureStart(); onChange(current.toMutableList().also { it.removeAt(idx) })
                        }
                    },
                    onTap = { pos ->
                        val idx = nearest(current, pos, size.width.toFloat(), size.height.toFloat(), 28.dp.toPx())
                        if (idx < 0 && current.size < 10) {
                            val p = CurvePoint(pos.x / size.width, 1f - pos.y / size.height)
                            onGestureStart(); onChange(ToneCurve.sanitise(current + p))
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pos ->
                        dragIndex = nearest(current, pos, size.width.toFloat(), size.height.toFloat(), 32.dp.toPx())
                        if (dragIndex >= 0) onGestureStart()
                    },
                    onDragEnd = { dragIndex = -1 },
                    onDragCancel = { dragIndex = -1 },
                ) { change, _ ->
                    val i = dragIndex
                    if (i < 0) return@detectDragGestures
                    change.consume()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val list = current.toMutableList()
                    // Keep x between neighbours so points never cross.
                    val minX = if (i == 0) 0f else list[i - 1].x + 0.02f
                    val maxX = if (i == list.lastIndex) 1f else list[i + 1].x - 0.02f
                    val x = when (i) {
                        0 -> (change.position.x / w).coerceIn(0f, maxX).coerceAtMost(0.4f)
                        list.lastIndex -> (change.position.x / w).coerceIn(minX, 1f).coerceAtLeast(0.6f)
                        else -> (change.position.x / w).coerceIn(minX, maxX)
                    }
                    list[i] = CurvePoint(x, (1f - change.position.y / h).coerceIn(0f, 1f))
                    onChange(list)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            drawLine(gridColor, Offset(w * i / 4f, 0f), Offset(w * i / 4f, h), 1.dp.toPx())
            drawLine(gridColor, Offset(0f, h * i / 4f), Offset(w, h * i / 4f), 1.dp.toPx())
        }
        drawLine(gridColor, Offset(0f, h), Offset(w, 0f), 1.dp.toPx())
        val lut = ToneCurve.lut(points)
        val path = Path()
        for (i in 0..255) {
            val x = i / 255f * w
            val y = h - lut[i] / 255f * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.5.dp.toPx()))
        points.forEachIndexed { idx, p ->
            val c = Offset(p.x * w, h - p.y * h)
            drawCircle(Color.White, if (idx == dragIndex) 10.dp.toPx() else 7.dp.toPx(), c)
            drawCircle(color, if (idx == dragIndex) 7.dp.toPx() else 5.dp.toPx(), c)
        }
    }
}

private fun nearest(points: List<CurvePoint>, pos: Offset, w: Float, h: Float, radius: Float): Int {
    var best = -1
    var bestD = radius
    points.forEachIndexed { i, p ->
        val d = (Offset(p.x * w, h - p.y * h) - pos).getDistance()
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}
