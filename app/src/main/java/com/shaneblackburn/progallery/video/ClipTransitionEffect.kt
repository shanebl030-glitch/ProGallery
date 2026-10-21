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

/**
 * Per-clip transition effect: fade (to black/white), blur and zoom applied in the clip's
 * own time window(s). Applied as a clip effect (not a composition effect) because a
 * composition effect receives per-clip timestamps that reset at each clip, so it can't tell
 * where a boundary falls on the timeline. Each clip instead carries its own [ClipEdge],
 * which knows the clip duration and the transitions at both ends.
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
    private var texelW = 0f
    private var texelH = 0f
    private var firstUs = Long.MIN_VALUE

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelW = 1f / inputWidth.coerceAtLeast(1)
        texelH = 1f / inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        if (firstUs == Long.MIN_VALUE) firstUs = presentationTimeUs
        val tMs = (presentationTimeUs - firstUs) / 1000
        val fx = edge.at(tMs)
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexel", floatArrayOf(texelW, texelH))
            program.setFloatUniform("uFade", fx.fade)
            program.setFloatUniform("uWhite", if (fx.white) 1f else 0f)
            program.setFloatUniform("uBlur", fx.blur)
            program.setFloatUniform("uZoom", fx.zoom)
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
        const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uFade;   // 0..1 blend toward the fade colour
uniform float uWhite;  // 1 = fade to white, 0 = to black
uniform float uBlur;   // 0..1
uniform float uZoom;   // 0..1
varying vec2 vTex;

void main() {
  vec2 uv = vTex;
  if (uZoom > 0.0) {
    float scale = 1.0 + uZoom * 0.3;        // punch in up to 30%
    uv = (uv - 0.5) / scale + 0.5;
  }
  vec3 c;
  if (uBlur > 0.0) {
    vec2 s = uTexel * (uBlur * 6.0);
    c  = texture2D(uTexSampler, uv).rgb * 0.25;
    c += texture2D(uTexSampler, uv + vec2( s.x, 0.0)).rgb * 0.125;
    c += texture2D(uTexSampler, uv + vec2(-s.x, 0.0)).rgb * 0.125;
    c += texture2D(uTexSampler, uv + vec2(0.0,  s.y)).rgb * 0.125;
    c += texture2D(uTexSampler, uv + vec2(0.0, -s.y)).rgb * 0.125;
    c += texture2D(uTexSampler, uv + vec2( s.x,  s.y)).rgb * 0.0625;
    c += texture2D(uTexSampler, uv + vec2(-s.x,  s.y)).rgb * 0.0625;
    c += texture2D(uTexSampler, uv + vec2( s.x, -s.y)).rgb * 0.0625;
    c += texture2D(uTexSampler, uv + vec2(-s.x, -s.y)).rgb * 0.0625;
  } else {
    c = texture2D(uTexSampler, uv).rgb;
  }
  if (uFade > 0.0) {
    vec3 target = vec3(uWhite);
    c = mix(c, target, uFade);
  }
  gl_FragColor = vec4(c, 1.0);
}
"""
    }
}
