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
import com.shaneblackburn.progallery.core.video.VideoGrade

/**
 * GPU colour grade for one clip: exposure, white balance, brightness, shadows/highlights,
 * contrast, saturation/vibrance and sharpening.
 *
 * The per-pixel maths mirrors [VideoGrade.apply] line for line (the device tests compare
 * exported pixels with that CPU reference), so keep the two in sync.
 */
@OptIn(UnstableApi::class)
class GradeEffect(private val grade: VideoGrade) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        GradeShaderProgram(grade, useHdr)
}

@OptIn(UnstableApi::class)
private class GradeShaderProgram(private val grade: VideoGrade, useHdr: Boolean) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var texelW = 0f
    private var texelH = 0f

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelW = 1f / inputWidth.coerceAtLeast(1)
        texelH = 1f / inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            program.setFloatsUniform("uTexel", floatArrayOf(texelW, texelH))
            program.setFloatUniform("uExpGain", grade.expGain)
            program.setFloatsUniform("uWb", floatArrayOf(grade.wbR, grade.wbG, grade.wbB))
            program.setFloatUniform("uBrightness", grade.brightness)
            program.setFloatUniform("uShadows", grade.shadows)
            program.setFloatUniform("uHighlights", grade.highlights)
            program.setFloatUniform("uContrast", grade.contrast)
            program.setFloatUniform("uSaturation", grade.saturation)
            program.setFloatUniform("uVibrance", grade.vibrance)
            program.setFloatUniform("uSharpness", grade.sharpness)
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
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""
        // Mirrors VideoGrade.apply(). Sharpening (unsharp mask on a 4-neighbour blur) runs first.
        const val FRAGMENT = """
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uExpGain;
uniform vec3 uWb;
uniform float uBrightness;
uniform float uShadows;
uniform float uHighlights;
uniform float uContrast;
uniform float uSaturation;
uniform float uVibrance;
uniform float uSharpness;
varying vec2 vTexSamplingCoord;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
  vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 c = src.rgb;
  if (uSharpness > 0.0) {
    vec3 blur = (texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexel.x, 0.0)).rgb
               + texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexel.x, 0.0)).rgb
               + texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexel.y)).rgb
               + texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexel.y)).rgb) * 0.25;
    c = clamp(c + (c - blur) * uSharpness, 0.0, 1.0);
  }
  if (uExpGain != 1.0) {
    c = pow(pow(max(c, vec3(0.0)), vec3(2.2)) * uExpGain, vec3(1.0 / 2.2));
  }
  c *= uWb;
  c += vec3(uBrightness);
  float l1 = clamp(luma(c), 0.0, 1.0);
  float tone = uShadows * (1.0 - smoothstep(0.0, 0.6, l1)) + uHighlights * smoothstep(0.4, 1.0, l1);
  c += vec3(tone);
  c = (c - 0.5) * uContrast + 0.5;
  float l2 = luma(c);
  float sat = clamp(max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b)), 0.0, 1.0);
  float f = uSaturation * (1.0 + uVibrance * (1.0 - sat));
  c = vec3(l2) + (c - vec3(l2)) * f;
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), src.a);
}
"""
    }
}
