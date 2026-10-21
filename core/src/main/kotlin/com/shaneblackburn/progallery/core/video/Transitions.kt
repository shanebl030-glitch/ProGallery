package com.shaneblackburn.progallery.core.video

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The transition library and a CPU reference implementation of the exact per-pixel maths the
 * GPU shader ([com.shaneblackburn.progallery.video.ClipTransitionEffect]) runs. The preview,
 * the export and the unit tests all consume this one definition, so they can never drift apart
 * (the same pattern [VideoGrade] uses for the colour grade).
 *
 * ARCHITECTURE — why every transition is a "through a state" effect:
 * A Media3 [androidx.media3.transformer.EditedMediaItemSequence] plays its clips strictly
 * back-to-back with no overlap, so the two clips of a transition are never on screen at the
 * same instant, and a composition-level effect receives per-clip timestamps that reset at each
 * clip (it can't tell where a boundary falls on the timeline). Transitions are therefore applied
 * PER CLIP: the outgoing clip's tail transforms toward a neutral state (black / blurred / zoomed
 * / off-screen / covered) and the incoming clip's head transforms back out of it, meeting at the
 * cut. This is reliable and matches the proven per-clip [GradeEffect] path.
 *
 * What this cannot do (needs a two-clip overlap compositor — see docs/SESSION_LOG.md):
 * a true motion cross-dissolve (both clips' live frames blended), a wipe/push that reveals the
 * OTHER clip's live frame rather than black, and J-/L-cuts (audio leading or trailing the visual
 * cut). [TransitionType.CROSS_DISSOLVE] is modelled here but marked [TransitionType.crossClip]
 * and is not offered in the picker in this build.
 */
enum class TransitionFamily(val label: String) {
    CUT("Cut"),
    DISSOLVE("Dissolve & fade"),
    BLUR("Blur & zoom"),
    MOVE("Movement"),
    REVEAL("Wipe & shape"),
    STYLE("Stylised"),
}

enum class TransitionType(
    val label: String,
    val family: TransitionFamily,
    val defaultDurationMs: Long = 1000L,
    val usesDirection: Boolean = false,
    val usesIntensity: Boolean = false,
    /** Needs both clips on screen at once; not renderable by the per-clip pipeline in this build. */
    val crossClip: Boolean = false,
) {
    NONE("Standard cut", TransitionFamily.CUT, 0L),
    FADE_BLACK("Fade through black", TransitionFamily.DISSOLVE),
    FADE_WHITE("Fade through white", TransitionFamily.DISSOLVE),
    BLUR("Blur", TransitionFamily.BLUR, usesIntensity = true),
    ZOOM("Zoom", TransitionFamily.BLUR, usesDirection = true, usesIntensity = true),
    CROSS_ZOOM("Cross zoom", TransitionFamily.BLUR, usesIntensity = true),
    WHIP_PAN("Whip pan", TransitionFamily.MOVE, usesDirection = true, usesIntensity = true),
    PUSH("Push / slide", TransitionFamily.MOVE, usesDirection = true),
    WIPE("Wipe", TransitionFamily.REVEAL, usesDirection = true),
    IRIS("Iris", TransitionFamily.REVEAL),
    LIGHT_LEAK("Light leak", TransitionFamily.STYLE, usesIntensity = true),
    GLITCH("Glitch", TransitionFamily.STYLE, usesIntensity = true),

    /** True cross dissolve — requires the two-clip overlap compositor; not selectable yet. */
    CROSS_DISSOLVE("Cross dissolve", TransitionFamily.DISSOLVE, crossClip = true),
    ;

    /** A brightness blend toward black/white (as opposed to a geometric/stylised effect). */
    val isFade: Boolean get() = this == FADE_BLACK || this == FADE_WHITE

    /** The directions that make sense for this type (empty when it has no direction). */
    fun directions(): List<TransitionDirection> = when (this) {
        ZOOM -> listOf(TransitionDirection.IN, TransitionDirection.OUT)
        WHIP_PAN, PUSH -> listOf(TransitionDirection.LEFT, TransitionDirection.RIGHT, TransitionDirection.UP, TransitionDirection.DOWN)
        WIPE -> listOf(TransitionDirection.LEFT, TransitionDirection.RIGHT, TransitionDirection.UP, TransitionDirection.DOWN, TransitionDirection.DIAGONAL)
        else -> emptyList()
    }

    /** The concrete direction used for rendering ([AUTO] resolved to this type's default). */
    fun resolve(d: TransitionDirection): TransitionDirection = when {
        d != TransitionDirection.AUTO -> d
        this == ZOOM -> TransitionDirection.IN
        this == WHIP_PAN -> TransitionDirection.LEFT
        this == PUSH -> TransitionDirection.LEFT
        this == WIPE -> TransitionDirection.RIGHT
        else -> TransitionDirection.RIGHT
    }

    companion object {
        /** Types a user can choose for a between-clip transition in this build (excludes cross-clip). */
        val selectable: List<TransitionType> get() = entries.filter { !it.crossClip }
    }
}

enum class TransitionDirection(val label: String) {
    AUTO("Auto"),
    LEFT("Left"), RIGHT("Right"), UP("Up"), DOWN("Down"), DIAGONAL("Diagonal"),
    IN("In"), OUT("Out"),
}

/**
 * A between-clip transition. [durationMs] is the total time the effect occupies, split evenly
 * across the cut (half on each clip). [direction] and [intensity] apply only to the types that
 * use them (see [TransitionType.usesDirection] / [TransitionType.usesIntensity]).
 */
data class Transition(
    val type: TransitionType,
    val durationMs: Long = type.defaultDurationMs,
    val direction: TransitionDirection = TransitionDirection.AUTO,
    val intensity: Float = 0.6f,
) {
    val isActive: Boolean get() = type != TransitionType.NONE && durationMs > 0

    companion object {
        val NONE = Transition(TransitionType.NONE, 0L)
        const val MIN_MS = 200L
        const val MAX_MS = 3000L
    }
}

/** A project fade in/out — a one-sided fade through black or white. */
data class Fade(val durationMs: Long, val toWhite: Boolean = false)

/**
 * Concrete render parameters for ONE frame. The GPU shader and [TransitionCpu] both consume
 * exactly these numbers, so the per-transition logic lives in one place ([TransitionSample.uniforms]).
 */
data class TransitionUniforms(
    /** Zoom about the centre (>1 punches in, <1 pulls back revealing black). */
    val scale: Float = 1f,
    /** Pan as a fraction of the frame (sampled coordinate is shifted by -off). */
    val offX: Float = 0f,
    val offY: Float = 0f,
    /** Gaussian blur radius, 0..1 (fraction of the frame). */
    val blur: Float = 0f,
    /** Blend toward (fadeR,fadeG,fadeB) by this amount, 0..1. */
    val fade: Float = 0f,
    val fadeR: Float = 0f,
    val fadeG: Float = 0f,
    val fadeB: Float = 0f,
    /** Wipe/iris coverage, 0..1 (how much of the frame is masked to black). */
    val cover: Float = 0f,
    /** 0 none, 1 wipe (directional line), 2 iris (circle). */
    val coverMode: Int = 0,
    /** Wipe line normal angle (radians): 0 = vertical line sweeping horizontally. */
    val coverAngle: Float = 0f,
    /** Warm additive bloom (light leak), 0..1, centred at (leakX,leakY). */
    val leak: Float = 0f,
    val leakX: Float = 0.5f,
    val leakY: Float = 0.4f,
    /** Glitch strength, 0..1 (block displacement + chromatic split). */
    val glitch: Float = 0f,
) {
    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        val IDENTITY = TransitionUniforms()
        /** Max blur radius as a fraction of the frame (shared by shader + CPU reference). */
        const val MAX_BLUR_UV = 0.05f
        /** Max zoom punch-in/out factor. */
        const val MAX_ZOOM = 0.8f
    }
}

/**
 * The transition state of one clip at one clip-local time: which effect, how far through it
 * ([edge]: 0 normal → 1 fully transformed at the cut), and whether this is the incoming clip's
 * head or the outgoing clip's tail (they transform in opposite directions for the directional
 * effects). [uniforms] turns this into concrete render parameters.
 */
data class TransitionSample(
    val type: TransitionType,
    val edge: Float,
    val incoming: Boolean,
    val direction: TransitionDirection = TransitionDirection.AUTO,
    val intensity: Float = 0.6f,
) {
    val isActive: Boolean get() = type != TransitionType.NONE && !type.crossClip && edge > 0f

    fun uniforms(): TransitionUniforms {
        if (!isActive) return TransitionUniforms.IDENTITY
        val e = edge.coerceIn(0f, 1f)
        val i = intensity.coerceIn(0f, 1f)
        val dir = type.resolve(direction)
        val maxZoom = TransitionUniforms.MAX_ZOOM
        // Unit screen vector for the resolved direction (x right, y down).
        val ax: Float
        val ay: Float
        when (dir) {
            TransitionDirection.LEFT -> { ax = -1f; ay = 0f }
            TransitionDirection.RIGHT -> { ax = 1f; ay = 0f }
            TransitionDirection.UP -> { ax = 0f; ay = -1f }
            TransitionDirection.DOWN -> { ax = 0f; ay = 1f }
            TransitionDirection.DIAGONAL -> { ax = 0.7071f; ay = 0.7071f }
            else -> { ax = 1f; ay = 0f }
        }
        return when (type) {
            TransitionType.FADE_BLACK -> TransitionUniforms(fade = e)
            TransitionType.FADE_WHITE -> TransitionUniforms(fade = e, fadeR = 1f, fadeG = 1f, fadeB = 1f)
            TransitionType.BLUR ->
                TransitionUniforms(blur = e * (0.45f + 0.55f * i) * TransitionUniforms.MAX_BLUR_UV)
            TransitionType.ZOOM -> {
                val amt = e * (0.35f + 0.65f * i) * maxZoom
                if (dir == TransitionDirection.OUT) TransitionUniforms(scale = 1f / (1f + amt))
                else TransitionUniforms(scale = 1f + amt)
            }
            TransitionType.CROSS_ZOOM -> TransitionUniforms(
                scale = 1f + e * (0.4f + 0.6f * i) * maxZoom,
                blur = e * (0.3f + 0.7f * i) * TransitionUniforms.MAX_BLUR_UV,
                fade = e * e * 0.18f, fadeR = 1f, fadeG = 1f, fadeB = 1f,
            )
            TransitionType.WHIP_PAN -> {
                val s = if (incoming) 1f else -1f
                TransitionUniforms(
                    offX = s * e * ax, offY = s * e * ay,
                    blur = e * (0.5f + 0.5f * i) * TransitionUniforms.MAX_BLUR_UV * 1.3f,
                )
            }
            TransitionType.PUSH -> {
                val s = if (incoming) 1f else -1f
                TransitionUniforms(offX = s * e * ax, offY = s * e * ay)
            }
            TransitionType.WIPE -> {
                val ang = when (dir) {
                    TransitionDirection.UP, TransitionDirection.DOWN -> (Math.PI.toFloat() / 2f)
                    TransitionDirection.DIAGONAL -> (Math.PI.toFloat() / 4f)
                    else -> 0f
                }
                // Up/left sweep from the opposite side so the band travels consistently.
                val flip = dir == TransitionDirection.LEFT || dir == TransitionDirection.UP
                TransitionUniforms(cover = e, coverMode = 1, coverAngle = ang + if (flip) Math.PI.toFloat() else 0f)
            }
            TransitionType.IRIS -> TransitionUniforms(cover = e, coverMode = 2)
            TransitionType.LIGHT_LEAK -> TransitionUniforms(
                leak = e * (0.5f + 0.5f * i),
                leakX = if (incoming) 0.35f else 0.65f, leakY = 0.4f,
                fade = e * e * 0.22f, fadeR = 1f, fadeG = 0.9f, fadeB = 0.72f,
            )
            TransitionType.GLITCH -> TransitionUniforms(glitch = e * (0.4f + 0.6f * i))
            else -> TransitionUniforms.IDENTITY
        }
    }

    companion object {
        val NONE = TransitionSample(TransitionType.NONE, 0f, false)
    }
}

/**
 * CPU reference renderer for the transition shader. Given a source sampler and the per-frame
 * [TransitionUniforms], it computes the output colour for one normalised pixel exactly as the
 * GLSL in ClipTransitionEffect does. Used by unit tests and by the local frame-renderer so the
 * transition maths is verified without a GPU.
 *
 * Coordinates are normalised 0..1 with (0,0) at the top-left; a source sample outside the frame
 * returns black.
 */
object TransitionCpu {
    private fun hash(x: Float): Float {
        val s = sin(x * 12.9898f) * 43758.547f
        return s - floor(s)
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** One texture tap; black outside the frame. [src] returns rgb (0..1) for an in-bounds (u,v). */
    private fun tap(src: (Float, Float) -> FloatArray, u: Float, v: Float, out: FloatArray) {
        if (u < 0f || u > 1f || v < 0f || v > 1f) { out[0] = 0f; out[1] = 0f; out[2] = 0f; return }
        val c = src(u, v); out[0] = c[0]; out[1] = c[1]; out[2] = c[2]
    }

    /** 9-tap box/gaussian blur in UV space; [blur] is the radius as a fraction of the frame. */
    private fun sampleBlur(src: (Float, Float) -> FloatArray, u: Float, v: Float, blur: Float): FloatArray {
        val acc = FloatArray(3)
        val t = FloatArray(3)
        if (blur <= 0f) { tap(src, u, v, acc); return acc }
        val s = blur
        fun add(du: Float, dv: Float, w: Float) { tap(src, u + du, v + dv, t); acc[0] += t[0] * w; acc[1] += t[1] * w; acc[2] += t[2] * w }
        add(0f, 0f, 0.25f)
        add(s, 0f, 0.125f); add(-s, 0f, 0.125f); add(0f, s, 0.125f); add(0f, -s, 0.125f)
        add(s, s, 0.0625f); add(-s, s, 0.0625f); add(s, -s, 0.0625f); add(-s, -s, 0.0625f)
        return acc
    }

    /** Renders one pixel at normalised ([x],[y]). [seed] animates the glitch (use 0 for tests). */
    fun render(u: TransitionUniforms, x: Float, y: Float, seed: Float, src: (Float, Float) -> FloatArray): FloatArray {
        var su = (x - 0.5f) / u.scale + 0.5f - u.offX
        val sv = (y - 0.5f) / u.scale + 0.5f - u.offY
        if (u.glitch > 0f) {
            val band = floor(y * 18f)
            val r = hash(band + seed)
            if (r > 1f - u.glitch * 0.6f) su += (hash(band * 1.7f + seed) - 0.5f) * u.glitch * 0.25f
        }
        val c = sampleBlur(src, su, sv, u.blur)
        if (u.glitch > 0f) {
            val off = u.glitch * 0.02f
            c[0] = sampleBlur(src, su + off, sv, u.blur)[0]
            c[2] = sampleBlur(src, su - off, sv, u.blur)[2]
        }
        // Wipe / iris coverage to black.
        var cover = 0f
        when (u.coverMode) {
            1 -> {
                val nx = cos(u.coverAngle); val ny = sin(u.coverAngle)
                val s = (x - 0.5f) * nx + (y - 0.5f) * ny + 0.5f
                cover = 1f - smoothstep(u.cover - 0.03f, u.cover + 0.03f, s)
            }
            2 -> {
                val dx = x - 0.5f; val dy = y - 0.5f
                val d = sqrt(dx * dx + dy * dy)
                val rad = (1f - u.cover) * 0.75f
                cover = smoothstep(rad, rad + 0.03f, d)
            }
        }
        if (cover > 0f) { c[0] *= (1f - cover); c[1] *= (1f - cover); c[2] *= (1f - cover) }
        // Light leak (warm additive bloom).
        if (u.leak > 0f) {
            val dx = x - u.leakX; val dy = y - u.leakY
            val d = sqrt(dx * dx + dy * dy)
            val g = (1f - smoothstep(0f, 0.7f, d)) * u.leak
            c[0] += g * 1.0f; c[1] += g * 0.9f; c[2] += g * 0.7f
        }
        // Fade toward the target colour.
        if (u.fade > 0f) {
            c[0] += (u.fadeR - c[0]) * u.fade
            c[1] += (u.fadeG - c[1]) * u.fade
            c[2] += (u.fadeB - c[2]) * u.fade
        }
        c[0] = c[0].coerceIn(0f, 1f); c[1] = c[1].coerceIn(0f, 1f); c[2] = c[2].coerceIn(0f, 1f)
        return c
    }

    /** Mean absolute neighbour difference of a rendered frame — a proxy for "detail / sharpness". */
    fun highFrequency(width: Int, height: Int, u: TransitionUniforms, src: (Float, Float) -> FloatArray): Float {
        var sum = 0f; var n = 0
        val prev = FloatArray(3)
        for (yy in 0 until height) {
            for (xx in 0 until width) {
                val c = render(u, (xx + 0.5f) / width, (yy + 0.5f) / height, 0f, src)
                if (xx > 0) { sum += abs(c[0] - prev[0]) + abs(c[1] - prev[1]) + abs(c[2] - prev[2]); n++ }
                prev[0] = c[0]; prev[1] = c[1]; prev[2] = c[2]
            }
        }
        return if (n == 0) 0f else sum / n
    }

    /** Mean luminance of a rendered frame (0..1). */
    fun meanLuma(width: Int, height: Int, u: TransitionUniforms, src: (Float, Float) -> FloatArray): Float {
        var sum = 0f
        for (yy in 0 until height) for (xx in 0 until width) {
            val c = render(u, (xx + 0.5f) / width, (yy + 0.5f) / height, 0f, src)
            sum += 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]
        }
        return sum / (width * height)
    }
}
