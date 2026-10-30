package com.shaneblackburn.progallery.video

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.shaneblackburn.progallery.core.video.ClipEdge
import com.shaneblackburn.progallery.core.video.TransitionUniforms

/**
 * DIAGNOSTIC-ONLY readout (v0.4.25.1-diag). Records what the GPU transition shader actually sees for
 * the clip it last rendered, so the live overlay (Sync check) can confirm the Media3 timestamp mapping
 * before the real timing fix is implemented. Writing these volatile fields does NOT affect rendering,
 * playback or export — the shader still uses the existing `firstUs` logic for the actual transition.
 */
object TransitionDiag {
    @Volatile var clipIndex: Int = -1
    @Volatile var ptsMs: Long = 0L          // presentationTimeUs the shader received, in ms (ground truth)
    @Volatile var firstUsMs: Long = 0L      // the first-frame anchor currently used, in ms
    @Volatile var clipStartMs: Long = 0L    // calculated clip start (sum of earlier clips' output durations)
    @Volatile var clipDurMs: Long = 0L      // this clip's output (timeline) duration
    @Volatile var tFirstUsMs: Long = 0L     // clip time the CURRENT code computes (pts - firstUs)
    @Volatile var tCalcMs: Long = 0L        // clip time the PROPOSED fix would compute (pts - clipStartMs)
}

/**
 * Per-clip transition effect: fades, blur, zoom, cross-zoom, whip pan, push/slide, wipe, iris,
 * light leak and glitch, applied in the clip's own time window(s).
 *
 * Applied as a CLIP effect (not a composition effect) because a composition effect receives
 * per-clip timestamps that reset at each clip, so it can't tell where a boundary falls on the
 * timeline. Each clip instead carries its own [ClipEdge], which knows the clip duration and the
 * transitions at both ends, and resolves to [TransitionUniforms] per frame — the SAME maths the
 * CPU reference (TransitionCpu) runs, so the preview, the export and the unit tests match.
 *
 * Placed last in a clip's video-effect list so the frame timestamps it sees are the clip's
 * 0-based output time (after speed/trim), matching [ClipEdge].
 *
 * [fixedTimeMs] handles single-frame extraction (the on-demand composed preview via Media3
 * FrameExtractor). There, only ONE frame is rendered per request, so the usual
 * "time since the first frame seen" logic would always read as t=0 and freeze the transition at its
 * start. When [fixedTimeMs] is supplied it returns the clip's output time to evaluate the transition
 * at — the caller already knows the exact playhead position it is extracting. It is read per frame
 * (it may be a live provider), so one effect instance can serve successive scrub positions without
 * being rebuilt. When null (continuous playback and export) the original frame-relative timing is used
 * unchanged, so those paths are byte-for-byte identical to before.
 */
@OptIn(UnstableApi::class)
class ClipTransitionEffect(
    private val edge: ClipEdge,
    private val fixedTimeMs: (() -> Long)? = null,
    // Diagnostic-only: the clip's index and its calculated start on the output timeline. Used solely to
    // populate [TransitionDiag]; they do NOT change how the transition is rendered (that still uses firstUs).
    private val clipStartMs: Long = 0L,
    private val clipIndex: Int = -1,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClipTransitionShaderProgram(edge, useHdr, fixedTimeMs, clipStartMs, clipIndex)

    companion object {
        fun needed(edge: ClipEdge) = edge.isActive
    }
}

@OptIn(UnstableApi::class)
private class ClipTransitionShaderProgram(
    private val edge: ClipEdge,
    useHdr: Boolean,
    private val fixedTimeMs: (() -> Long)? = null,
    private val clipStartMs: Long = 0L,
    private val clipIndex: Int = -1,
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var firstUs = Long.MIN_VALUE

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val tMs = fixedTimeMs?.invoke() ?: run {
            // Continuous playback / export: time since the first frame this program saw, which is the
            // clip's 0-based output time.
            if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
            (presentationTimeUs - firstUs) / 1000
        }
        val u: TransitionUniforms = edge.uniformsAt(tMs)
        // DIAGNOSTIC (read-only): record what this clip's shader actually saw so the Sync-check overlay
        // can confirm the timestamp mapping. Does not influence the uniforms/rendering above or below.
        if (clipIndex >= 0) {
            TransitionDiag.clipIndex = clipIndex
            TransitionDiag.ptsMs = presentationTimeUs / 1000
            TransitionDiag.firstUsMs = if (firstUs == Long.MIN_VALUE) 0L else firstUs / 1000
            TransitionDiag.clipStartMs = clipStartMs
            TransitionDiag.clipDurMs = edge.durationMs
            TransitionDiag.tFirstUsMs = tMs
            TransitionDiag.tCalcMs = presentationTimeUs / 1000 - clipStartMs
        }
        // A seed that changes a few times a second, so the glitch flickers.
        val seed = ((presentationTimeUs / 60_000L) % 997L).toFloat()
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uScale", u.scale)
            program.setFloatsUniform("uOff", floatArrayOf(u.offX, u.offY))
            program.setFloatUniform("uBlur", u.blur)
            program.setFloatUniform("uFade", u.fade)
            program.setFloatsUniform("uFadeColor", floatArrayOf(u.fadeR, u.fadeG, u.fadeB))
            program.setFloatUniform("uCover", u.cover)
            program.setFloatUniform("uCoverMode", u.coverMode.toFloat())
            program.setFloatUniform("uCoverAngle", u.coverAngle)
            program.setFloatUniform("uLeak", u.leak)
            program.setFloatsUniform("uLeakPos", floatArrayOf(u.leakX, u.leakY))
            program.setFloatUniform("uGlitch", u.glitch)
            program.setFloatUniform("uRotate", u.rotate)
            program.setFloatUniform("uPixel", u.pixel)
            program.setFloatUniform("uShake", u.shake)
            program.setFloatUniform("uRipple", u.ripple)
            program.setFloatUniform("uSeed", seed)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try { program.delete() } catch (e: GlUtil.GlException) { throw VideoFrameProcessingException(e) }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTex;
void main() {
  gl_Position = aFramePosition;
  vTex = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        /** Mirrors com.shaneblackburn.progallery.core.video.TransitionCpu.render exactly. */
        const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform float uScale;
uniform vec2 uOff;
uniform float uBlur;
uniform float uFade;
uniform vec3 uFadeColor;
uniform float uCover;
uniform float uCoverMode;   // 0 none, 1 wipe, 2 iris
uniform float uCoverAngle;
uniform float uLeak;
uniform vec2 uLeakPos;
uniform float uGlitch;
uniform float uRotate;
uniform float uPixel;
uniform float uShake;
uniform float uRipple;
uniform float uSeed;
varying vec2 vTex;

float hashf(float x) { return fract(sin(x * 12.9898) * 43758.5453); }

vec3 tap(vec2 uv) {
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0);
  return texture2D(uTexSampler, uv).rgb;
}

// 49-tap (7x7) Gaussian blur. The weight exp(-(i*i+j*j)/8) (sigma ~2 taps) is computed
// ARITHMETICALLY rather than read from a weights array indexed by the loop variable: GL ES 1.00
// drivers (notably the CI emulator's software GL) index such arrays unreliably, which left the
// weights wrong and the blur RAISING high-frequency instead of reducing it. Taps span +/- uBlur at
// step = uBlur/3, dense enough to genuinely soften rather than ghost. In lockstep with
// TransitionCpu.sampleBlur.
vec3 sampleBlur(vec2 uv) {
  if (uBlur <= 0.0) return tap(uv);
  // Centre off-frame (content slid off for whip/push): stay black, like a plain tap.
  if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec3(0.0);
  // Otherwise EDGE-CLAMP neighbour taps to [0,1] rather than sampling off-frame black: a black
  // border halo *adds* high-frequency at the edges and defeats the blur (observed on-device: BLUR
  // hf stayed above the plain cut). Clamping repeats the edge pixel — the standard artifact-free
  // blur. texture2D directly (not tap()) so the clamped, in-bounds coords aren't re-zeroed.
  float step = uBlur / 3.0;
  vec3 acc = vec3(0.0);
  float wsum = 0.0;
  for (int i = -3; i <= 3; i++) {
    for (int j = -3; j <= 3; j++) {
      float d2 = float(i * i + j * j);
      float w = exp(-d2 / 8.0);
      vec2 c = clamp(uv + vec2(float(i), float(j)) * step, 0.0, 1.0);
      acc += texture2D(uTexSampler, c).rgb * w;
      wsum += w;
    }
  }
  return acc / wsum;
}

void main() {
  vec2 p = vTex;
  vec2 d = p - 0.5;
  if (uRotate != 0.0) {
    float cs = cos(uRotate); float sn = sin(uRotate);
    d = vec2(d.x * cs - d.y * sn, d.x * sn + d.y * cs);
  }
  vec2 uv = d / uScale + 0.5 - uOff;
  if (uShake > 0.0) {
    uv.x += (hashf(floor(uSeed) * 3.1) - 0.5) * uShake;
    uv.y += (hashf(floor(uSeed) * 5.7 + 1.3) - 0.5) * uShake;
  }
  if (uRipple > 0.0) {
    float phase = uSeed * 0.6;
    float du = sin(uv.y * 22.0 + phase) * uRipple;
    float dv = sin(uv.x * 22.0 + phase * 1.3) * uRipple;
    uv.x += du; uv.y += dv;
  }
  if (uPixel > 0.0) {
    uv = (floor(uv / uPixel) + 0.5) * uPixel;
  }
  if (uGlitch > 0.0) {
    float band = floor(p.y * 18.0);
    float r = hashf(band + uSeed);
    if (r > 1.0 - uGlitch * 0.6) uv.x += (hashf(band * 1.7 + uSeed) - 0.5) * uGlitch * 0.25;
  }
  vec3 c = sampleBlur(uv);
  if (uGlitch > 0.0) {
    float off = uGlitch * 0.02;
    c.r = sampleBlur(uv + vec2(off, 0.0)).r;
    c.b = sampleBlur(uv - vec2(off, 0.0)).b;
  }
  float cover = 0.0;
  if (uCoverMode > 1.5) {
    // Iris: everything outside a shrinking circle goes black.
    float d = distance(p, vec2(0.5));
    float rad = (1.0 - uCover) * 0.75;
    cover = smoothstep(rad, rad + 0.03, d);
  } else if (uCoverMode > 0.5) {
    // Wipe: a moving line; the side with coordinate < uCover is black.
    vec2 n = vec2(cos(uCoverAngle), sin(uCoverAngle));
    float s = dot(p - 0.5, n) + 0.5;
    cover = 1.0 - smoothstep(uCover - 0.03, uCover + 0.03, s);
  }
  c *= (1.0 - cover);
  if (uLeak > 0.0) {
    float d = distance(p, uLeakPos);
    float g = (1.0 - smoothstep(0.0, 0.7, d)) * uLeak;
    c += vec3(1.0, 0.9, 0.7) * g;
  }
  c = mix(c, uFadeColor, uFade);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
