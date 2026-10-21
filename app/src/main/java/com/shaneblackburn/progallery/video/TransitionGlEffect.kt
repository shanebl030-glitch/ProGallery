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
import com.shaneblackburn.progallery.core.video.TransitionCurve
import com.shaneblackburn.progallery.core.video.VideoProject

/**
 * Time-windowed blur / zoom transitions at clip boundaries. Single-stream (no overlap),
 * so it works identically in the live preview and the export. The per-frame blur and zoom
 * strengths come from [TransitionCurve], which peaks at the boundary and tapers over the
 * transition's half-duration.
 */
@OptIn(UnstableApi::class)
class TransitionGlEffect(private val project: VideoProject) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        TransitionShaderProgram(project, useHdr)

    companion object {
        fun needed(project: VideoProject) = TransitionCurve.needed(project)
    }
}

@OptIn(UnstableApi::class)
private class TransitionShaderProgram(private val project: VideoProject, useHdr: Boolean) :
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
        val fx = TransitionCurve.at(project, tMs)
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexel", floatArrayOf(texelW, texelH))
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
        // Zoom: punch in toward the centre by up to 30% at the boundary.
        // Blur: 9-tap average whose spread grows with the blur strength (up to ~6 texels).
        const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uBlur;
uniform float uZoom;
varying vec2 vTex;

void main() {
  vec2 uv = vTex;
  if (uZoom > 0.0) {
    float scale = 1.0 + uZoom * 0.3;
    uv = (uv - 0.5) / scale + 0.5;
  }
  if (uBlur > 0.0) {
    vec2 s = uTexel * (uBlur * 6.0);
    vec3 c = vec3(0.0);
    c += texture2D(uTexSampler, uv).rgb * 0.25;
    c += texture2D(uTexSampler, uv + vec2( s.x, 0.0)).rgb * 0.125;
    c += texture2D(uTexSampler, uv + vec2(-s.x, 0.0)).rgb * 0.125;
    c += texture2D(uTexSampler, uv + vec2(0.0,  s.y)).rgb * 0.125;
    c += texture2D(uTexSampler, uv + vec2(0.0, -s.y)).rgb * 0.125;
    c += texture2D(uTexSampler, uv + vec2( s.x,  s.y)).rgb * 0.0625;
    c += texture2D(uTexSampler, uv + vec2(-s.x,  s.y)).rgb * 0.0625;
    c += texture2D(uTexSampler, uv + vec2( s.x, -s.y)).rgb * 0.0625;
    c += texture2D(uTexSampler, uv + vec2(-s.x, -s.y)).rgb * 0.0625;
    gl_FragColor = vec4(c, 1.0);
  } else {
    gl_FragColor = texture2D(uTexSampler, uv);
  }
}
"""
    }
}
