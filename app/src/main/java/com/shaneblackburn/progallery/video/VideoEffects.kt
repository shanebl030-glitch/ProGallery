package com.shaneblackburn.progallery.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.RgbMatrix
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.FadeCurve
import com.shaneblackburn.progallery.core.video.TextAnimation
import com.shaneblackburn.progallery.core.video.VideoColor
import com.shaneblackburn.progallery.core.video.VideoFilter
import com.shaneblackburn.progallery.core.video.VideoProject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/** 3x3 RGB matrix + offset, composed on the CPU and handed to the GPU as a 4x4. */
data class ColorXform(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), val o: FloatArray = floatArrayOf(0f, 0f, 0f)) {
    /** Returns a transform that applies this one first, then [next]. */
    fun then(next: ColorXform): ColorXform {
        val a = next.m
        val b = m
        val r = FloatArray(9)
        for (i in 0..2) for (j in 0..2) r[i * 3 + j] = (0..2).sumOf { k -> (a[i * 3 + k] * b[k * 3 + j]).toDouble() }.toFloat()
        val off = FloatArray(3) { i -> (0..2).sumOf { k -> (a[i * 3 + k] * o[k]).toDouble() }.toFloat() + next.o[i] }
        return ColorXform(r, off)
    }

    /** Column-major 4x4 for Media3 [RgbMatrix]: the alpha=1 column carries the offset. */
    fun toGl(): FloatArray = floatArrayOf(
        m[0], m[3], m[6], 0f,
        m[1], m[4], m[7], 0f,
        m[2], m[5], m[8], 0f,
        o[0], o[1], o[2], 1f,
    )

    override fun equals(other: Any?) = other is ColorXform && m.contentEquals(other.m) && o.contentEquals(other.o)
    override fun hashCode() = m.contentHashCode() * 31 + o.contentHashCode()

    companion object {
        private const val LR = 0.2126f
        private const val LG = 0.7152f
        private const val LB = 0.0722f

        fun scale(s: Float) = ColorXform(floatArrayOf(s, 0f, 0f, 0f, s, 0f, 0f, 0f, s))
        fun offset(v: Float) = ColorXform(o = floatArrayOf(v, v, v))
        fun gains(r: Float, g: Float, b: Float) = ColorXform(floatArrayOf(r, 0f, 0f, 0f, g, 0f, 0f, 0f, b))

        fun saturation(s: Float): ColorXform {
            val ir = (1 - s) * LR; val ig = (1 - s) * LG; val ib = (1 - s) * LB
            return ColorXform(floatArrayOf(ir + s, ig, ib, ir, ig + s, ib, ir, ig, ib + s))
        }

        /** Contrast around mid-grey: (x - 0.5) * c + 0.5. */
        fun contrast(c: Float) = ColorXform(floatArrayOf(c, 0f, 0f, 0f, c, 0f, 0f, 0f, c), floatArrayOf(0.5f * (1 - c), 0.5f * (1 - c), 0.5f * (1 - c)))

        fun forColor(c: VideoColor): ColorXform {
            var x = ColorXform()
            if (c.temperature != 0f) x = x.then(gains(1f + 0.2f * c.temperature, 1f, 1f - 0.2f * c.temperature))
            if (c.brightness != 0f) x = x.then(offset(c.brightness * 0.25f))
            if (c.contrast != 0f) x = x.then(contrast(1f + c.contrast * 0.6f))
            if (c.saturation != 0f) x = x.then(saturation(1f + c.saturation))
            return x
        }

        fun forFilter(f: VideoFilter): ColorXform = when (f) {
            VideoFilter.NONE -> ColorXform()
            VideoFilter.MONO -> saturation(0f)
            VideoFilter.NOIR -> saturation(0f).then(contrast(1.35f))
            VideoFilter.SEPIA -> ColorXform(floatArrayOf(0.393f, 0.769f, 0.189f, 0.349f, 0.686f, 0.168f, 0.272f, 0.534f, 0.131f))
            VideoFilter.WARM -> gains(1.08f, 1.0f, 0.88f)
            VideoFilter.COOL -> gains(0.9f, 1.0f, 1.1f)
            VideoFilter.VIVID -> saturation(1.35f).then(contrast(1.1f))
            VideoFilter.FADED -> saturation(0.75f).then(contrast(0.82f)).then(offset(0.04f))
            VideoFilter.CINEMATIC -> contrast(1.12f).then(gains(1.02f, 0.98f, 1.06f)).then(saturation(0.9f))
        }

        /** Blend towards black (toWhite=false) or white by [amount]. */
        fun fade(amount: Float, toWhite: Boolean): ColorXform {
            val k = 1f - amount
            return if (toWhite) ColorXform(floatArrayOf(k, 0f, 0f, 0f, k, 0f, 0f, 0f, k), floatArrayOf(amount, amount, amount)) else scale(k)
        }
    }
}

/** Static colour/filter matrix for one clip. */
@OptIn(UnstableApi::class)
class ClipColorMatrix(clip: Clip) : RgbMatrix {
    private val matrix = ColorXform.forColor(clip.color).then(ColorXform.forFilter(clip.filter)).toGl()
    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray = matrix

    companion object {
        fun needed(clip: Clip) = !clip.color.isNeutral || clip.filter != VideoFilter.NONE
    }
}

/**
 * Timeline fades and fade-through transitions, applied to the whole composition.
 * Times are measured from the first frame this effect sees, which is timeline 0.
 */
@OptIn(UnstableApi::class)
class TimelineFadeMatrix(private val project: VideoProject) : RgbMatrix {
    private var firstUs = Long.MIN_VALUE
    private val identity = ColorXform().toGl()

    override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1000
        val (amount, white) = FadeCurve.at(project, t)
        return if (amount <= 0f) identity else ColorXform.fade(amount, white).toGl()
    }

    companion object {
        fun needed(p: VideoProject) = p.fadeIn != null || p.fadeOut != null ||
            p.transitions.any { it.type != com.shaneblackburn.progallery.core.video.TransitionType.NONE }
    }
}

/**
 * Draws all timed texts visible at the current frame into a full-frame bitmap.
 * Re-renders only when the visible set or its animation state changes.
 */
@OptIn(UnstableApi::class)
class TimedTextOverlay(private val project: VideoProject, private val width: Int, private val height: Int) : BitmapOverlay() {
    private var firstUs = Long.MIN_VALUE
    private val bitmap = Bitmap.createBitmap(max(2, width), max(2, height), Bitmap.Config.ARGB_8888)
    private var lastKey: String? = null
    private val settings = OverlaySettings.Builder().build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val t = (presentationTimeUs - firstUs) / 1000
        val visible = project.texts.filter { it.isVisibleAt(t) }
        val key = visible.joinToString("|") { "${it.id}:${(it.alphaAt(t) * 20).roundToInt()}" }
        if (key != lastKey) {
            lastKey = key
            bitmap.eraseColor(0)
            val c = Canvas(bitmap)
            visible.forEach { tt -> drawTimedText(c, tt, t, width.toFloat(), height.toFloat()) }
        }
        return bitmap
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    companion object {
        fun drawTimedText(c: Canvas, tt: com.shaneblackburn.progallery.core.video.TimedText, t: Long, w: Float, h: Float) {
            val alpha = tt.alphaAt(t)
            if (alpha <= 0f) return
            val anim = 1f - alpha
            val size = tt.size * h * if (tt.animation == TextAnimation.POP) (0.7f + 0.3f * alpha) else 1f
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                color = tt.color
                this.alpha = (alpha * 255).roundToInt()
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                if (!tt.background) setShadowLayer(size * 0.1f, 0f, size * 0.05f, android.graphics.Color.argb((alpha * 160).roundToInt(), 0, 0, 0))
            }
            val lines = tt.text.lines()
            val lineH = paint.fontSpacing
            val dy = if (tt.animation == TextAnimation.SLIDE_UP) anim * h * 0.05f else 0f
            val cx = tt.x * w
            val top = tt.y * h - lines.size * lineH / 2f + dy
            if (tt.background) {
                val textW = lines.maxOf { paint.measureText(it) }
                val pad = size * 0.35f
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((alpha * 150).roundToInt(), 0, 0, 0) }
                c.drawRoundRect(RectF(cx - textW / 2 - pad, top - pad * 0.5f, cx + textW / 2 + pad, top + lines.size * lineH + pad * 0.5f), pad, pad, bg)
            }
            lines.forEachIndexed { i, line ->
                c.drawText(line, cx, top + i * lineH - paint.fontMetrics.ascent, paint)
            }
        }
    }
}

/**
 * Applies a gain envelope to PCM audio: a constant volume with optional fade in/out
 * over a total duration. Handles 16-bit and float PCM.
 */
@OptIn(UnstableApi::class)
class GainEnvelopeProcessor(
    private val volume: Float,
    private val fadeInMs: Long = 0,
    private val fadeOutMs: Long = 0,
    private val totalMs: Long = Long.MAX_VALUE,
) : BaseAudioProcessor() {
    private var framesProcessed = 0L
    private var sampleRate = 44100
    private var channels = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val float = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (float) 4 else 2
        val out = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        inputBuffer.order(ByteOrder.nativeOrder())
        val frameBytes = bytesPerSample * channels
        while (inputBuffer.remaining() >= frameBytes) {
            val tMs = framesProcessed * 1000 / sampleRate
            val g = gainAt(tMs)
            repeat(channels) {
                if (float) out.putFloat((inputBuffer.float * g).coerceIn(-1f, 1f))
                else out.putShort((inputBuffer.short * g).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            framesProcessed++
        }
        // Pass through any trailing partial frame unchanged.
        while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
        out.flip()
    }

    private fun gainAt(t: Long): Float {
        var g = volume
        if (fadeInMs > 0 && t < fadeInMs) g *= t.toFloat() / fadeInMs
        if (fadeOutMs > 0 && totalMs != Long.MAX_VALUE && t > totalMs - fadeOutMs) g *= ((totalMs - t).toFloat() / fadeOutMs).coerceIn(0f, 1f)
        if (t > totalMs) g = 0f
        return g
    }

    override fun onFlush() { framesProcessed = 0 }
    override fun onReset() { framesProcessed = 0 }

}
