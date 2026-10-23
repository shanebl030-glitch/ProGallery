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
 */
@OptIn(UnstableApi::class)
class ClipTransitionEffect(private val edge: ClipEdge) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClipTransitionShaderProgram(edge, useHdr)

    companion object {
        fun needed(edge: ClipEdge) = edge.isActive
    }
}

@OptIn(UnstableApi::class)
private class ClipTransitionShaderProgram(private val edge: ClipEdge, useHdr: Boolean) :
    BaseGlShaderProgram(useHdr, 1) {

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
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val tMs = (presentationTimeUs - firstUs) / 1000
        val u: TransitionUniforms = edge.uniformsAt(tMs)
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
