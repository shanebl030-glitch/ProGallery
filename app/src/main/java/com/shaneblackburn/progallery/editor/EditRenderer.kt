package com.shaneblackburn.progallery.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.shaneblackburn.progallery.core.edit.Brush
import com.shaneblackburn.progallery.core.edit.EditState
import com.shaneblackburn.progallery.core.edit.FontChoice
import com.shaneblackburn.progallery.core.edit.Frame
import com.shaneblackburn.progallery.core.edit.FrameMath
import com.shaneblackburn.progallery.core.edit.FrameStyle
import com.shaneblackburn.progallery.core.edit.Geometry
import com.shaneblackburn.progallery.core.edit.Overlay
import com.shaneblackburn.progallery.core.edit.PixelPipeline
import com.shaneblackburn.progallery.core.edit.ShapeKind
import com.shaneblackburn.progallery.core.edit.TextAlignChoice
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns an original image + [EditState] into pixels. Shared by the live preview (small
 * source) and export (full resolution), so the result matches what the user saw.
 */
object EditRenderer {

    /** Hard cap for export to avoid out-of-memory on huge (100+ MP) images. */
    const val MAX_EXPORT_PIXELS = 48_000_000L

    /**
     * Decodes [uri] upright (EXIF orientation applied) into a mutable ARGB bitmap whose
     * longer side is at most [maxSide] (0 = full size, still capped by [maxPixels]).
     */
    fun decode(resolver: ContentResolver, uri: Uri, maxSide: Int, maxPixels: Long = MAX_EXPORT_PIXELS): Bitmap {
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            var scale = 1.0
            if (maxSide in 1 until max(w, h)) scale = maxSide.toDouble() / max(w, h)
            val pixels = w.toLong() * h
            if (pixels * scale * scale > maxPixels) scale = sqrt(maxPixels.toDouble() / pixels)
            if (scale < 1.0) decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
        }.let { bmp ->
            // HDR / 16-bit sources decode as RGBA_F16; the pipeline works in 8-bit ARGB.
            if (bmp.config == Bitmap.Config.ARGB_8888) bmp
            else bmp.copy(Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }
        }
    }

    /** Applies rotation, flip, straighten and crop. Returns a new bitmap (input untouched). */
    fun applyGeometry(src: Bitmap, g: Geometry): Bitmap {
        val (ow, oh) = g.orientedSize(src.width, src.height)
        val cropW = max(1, (g.crop.width * ow).roundToInt())
        val cropH = max(1, (g.crop.height * oh).roundToInt())
        if (g == Geometry()) return src.copy(Bitmap.Config.ARGB_8888, true)
        val out = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        // Centre source at origin, rotate quarter turns, flip, then straighten with
        // auto-zoom so no empty corners appear, then move the crop into view.
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postRotate(g.quarterTurns * 90f)
        m.postScale(if (g.flipH) -1f else 1f, if (g.flipV) -1f else 1f)
        if (g.straighten != 0f) {
            val zoom = straightenZoom(ow, oh, g.straighten)
            m.postRotate(g.straighten)
            m.postScale(zoom, zoom)
        }
        m.postTranslate(ow / 2f, oh / 2f)
        m.postTranslate(-g.crop.left * ow, -g.crop.top * oh)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Scale needed so a [w]×[h] image rotated by [degrees] still fills its frame. */
    fun straightenZoom(w: Int, h: Int, degrees: Float): Float {
        val a = Math.toRadians(abs(degrees).toDouble())
        val c = cos(a)
        val s = sin(a)
        val sx = (w * c + h * s) / w
        val sy = (w * s + h * c) / h
        return max(sx, sy).toFloat()
    }

    /** Runs tone/colour/detail edits in place on [bmp]. */
    fun applyPixels(
        bmp: Bitmap,
        state: EditState,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ) {
        val pipeline = PixelPipeline(state, min(bmp.width, bmp.height))
        if (pipeline.isIdentity) return
        val w = bmp.width
        pipeline.render(
            w, bmp.height,
            read = { y, rows, dest -> bmp.getPixels(dest, 0, w, 0, y, w, rows) },
            write = { y, rows, src -> bmp.setPixels(src, 0, w, 0, y, w, rows) },
            stripRows = 192,
            isCancelled = isCancelled,
            onProgress = onProgress,
        )
    }

    /** Draws vector overlays (normalised to the output image) onto [bmp]. */
    fun drawOverlays(bmp: Bitmap, overlays: List<Overlay>) {
        if (overlays.isEmpty()) return
        val canvas = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val short = min(w, h)
        // Strokes with the eraser only erase other strokes, so draw strokes on a layer.
        val strokes = overlays.filterIsInstance<Overlay.Stroke>()
        if (strokes.isNotEmpty()) {
            val layer = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            val lc = Canvas(layer)
            strokes.forEach { drawStroke(lc, it, w, h, short) }
            canvas.drawBitmap(layer, 0f, 0f, null)
            layer.recycle()
        }
        overlays.forEach { o ->
            when (o) {
                is Overlay.Stroke -> Unit
                is Overlay.Shape -> drawShape(canvas, o, w, h, short)
                is Overlay.Text -> drawText(canvas, o, w, h, short)
                is Overlay.Sticker -> drawSticker(canvas, o, w, h, short)
            }
        }
    }

    fun drawStroke(c: Canvas, s: Overlay.Stroke, w: Float, h: Float, short: Float) {
        if (s.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = s.width * short
            color = s.color
            when (s.brush) {
                Brush.MARKER -> alpha = 220
                Brush.HIGHLIGHTER -> { alpha = 110; strokeCap = Paint.Cap.SQUARE }
                Brush.ERASER -> xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                Brush.PEN -> Unit
            }
        }
        val path = Path()
        val first = s.points.first()
        path.moveTo(first.x * w, first.y * h)
        if (s.points.size == 1) path.lineTo(first.x * w + 0.1f, first.y * h)
        for (i in 1 until s.points.size) {
            val p0 = s.points[i - 1]
            val p1 = s.points[i]
            // Quadratic smoothing through midpoints.
            path.quadTo(p0.x * w, p0.y * h, (p0.x + p1.x) / 2f * w, (p0.y + p1.y) / 2f * h)
        }
        val last = s.points.last()
        path.lineTo(last.x * w, last.y * h)
        c.drawPath(path, paint)
    }

    fun drawShape(c: Canvas, s: Overlay.Shape, w: Float, h: Float, short: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color
            strokeWidth = s.width * short
            style = if (s.filled && s.kind != ShapeKind.LINE && s.kind != ShapeKind.ARROW) Paint.Style.FILL else Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val x0 = s.start.x * w; val y0 = s.start.y * h
        val x1 = s.end.x * w; val y1 = s.end.y * h
        val rect = RectF(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
        when (s.kind) {
            ShapeKind.LINE -> c.drawLine(x0, y0, x1, y1, paint)
            ShapeKind.RECT -> c.drawRect(rect, paint)
            ShapeKind.OVAL -> c.drawOval(rect, paint)
            ShapeKind.ARROW -> {
                c.drawLine(x0, y0, x1, y1, paint)
                val angle = atan2(y1 - y0, x1 - x0)
                val head = max(paint.strokeWidth * 4f, short * 0.025f)
                val path = Path().apply {
                    moveTo(x1, y1)
                    lineTo(x1 - head * cos(angle - 0.45f), y1 - head * sin(angle - 0.45f))
                    moveTo(x1, y1)
                    lineTo(x1 - head * cos(angle + 0.45f), y1 - head * sin(angle + 0.45f))
                }
                c.drawPath(path, paint)
            }
        }
    }

    fun typeface(font: FontChoice): Typeface = when (font) {
        FontChoice.SANS -> Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        FontChoice.SANS_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        FontChoice.SERIF -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        FontChoice.MONO -> Typeface.MONOSPACE
        FontChoice.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        FontChoice.SCRIPT -> Typeface.create("cursive", Typeface.NORMAL)
    }

    fun drawText(c: Canvas, t: Overlay.Text, w: Float, h: Float, short: Float) {
        if (t.text.isBlank()) return
        val size = t.size * short
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            typeface = typeface(t.font)
            color = t.color
        }
        val align = when (t.align) {
            TextAlignChoice.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignChoice.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignChoice.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val maxWidth = (w * 0.95f).roundToInt().coerceAtLeast(1)
        val textWidth = t.text.lines().maxOf { paint.measureText(it) }.coerceAtMost(maxWidth.toFloat()).roundToInt().coerceAtLeast(1)
        fun layout(p: TextPaint) = StaticLayout.Builder.obtain(t.text, 0, t.text.length, p, textWidth)
            .setAlignment(align).setIncludePad(false).build()
        val fill = layout(paint)
        c.save()
        c.translate(t.x * w, t.y * h)
        c.rotate(t.rotation)
        c.translate(-fill.width / 2f, -fill.height / 2f)
        t.backgroundColor?.let { bg ->
            val pad = size * 0.3f
            c.drawRoundRect(RectF(-pad, -pad * 0.6f, fill.width + pad, fill.height + pad * 0.6f), pad, pad,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        }
        if (t.shadow) paint.setShadowLayer(size * 0.08f, 0f, size * 0.04f, 0x99000000.toInt())
        t.outlineColor?.let { oc ->
            val stroke = TextPaint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * t.outlineWidth
                strokeJoin = Paint.Join.ROUND
                color = oc
            }
            layout(stroke).draw(c)
            paint.clearShadowLayer()
        }
        layout(paint).draw(c)
        c.restore()
    }

    fun drawSticker(c: Canvas, s: Overlay.Sticker, w: Float, h: Float, short: Float) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = s.size * short; textAlign = Paint.Align.CENTER }
        val fm = paint.fontMetrics
        c.save()
        c.translate(s.x * w, s.y * h)
        c.rotate(s.rotation)
        c.drawText(s.glyph, 0f, -(fm.ascent + fm.descent) / 2f, paint)
        c.restore()
    }

    /** Places [photo] into a frame, returning a new bitmap (or the input if no frame). */
    fun applyFrame(photo: Bitmap, frame: Frame?): Bitmap {
        if (frame == null) return photo
        val (size, inset) = FrameMath.layout(frame, photo.width, photo.height)
        val out = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(frame.color)
        val dst = RectF(inset[0].toFloat(), inset[1].toFloat(), (inset[0] + inset[2]).toFloat(), (inset[1] + inset[3]).toFloat())
        val radius = when (frame.style) {
            FrameStyle.ROUNDED -> max(frame.cornerRadius, 0.04f) * min(photo.width, photo.height)
            else -> frame.cornerRadius * min(photo.width, photo.height)
        }
        if (radius > 0f) {
            val shader = android.graphics.BitmapShader(photo, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply { postTranslate(dst.left, dst.top) })
            c.drawRoundRect(dst, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        } else {
            c.drawBitmap(photo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /**
     * Full render: geometry → pixels → overlays → frame. Consumes nothing; the caller
     * owns [source]. Returns a new bitmap.
     */
    fun render(
        source: Bitmap,
        state: EditState,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
    ): Bitmap {
        val geo = applyGeometry(source, state.geometry)
        applyPixels(geo, state, isCancelled) { onProgress(it * 0.9f) }
        drawOverlays(geo, state.overlays)
        val framed = applyFrame(geo, state.frame)
        if (framed !== geo) geo.recycle()
        onProgress(1f)
        return framed
    }
}
