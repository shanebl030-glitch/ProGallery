package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.video.AudioEnvelope
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Fade
import com.shaneblackburn.progallery.core.video.FadeCurve
import com.shaneblackburn.progallery.core.video.TimedText
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VideoProjectTest {
    private fun clip(id: Long, dur: Long) = Clip(id, "content://v/$id", "v$id.mp4", dur, 1920, 1080)

    @Test fun durationWithTrimAndSpeed() {
        val c = clip(1, 10_000).withTrim(2_000, 8_000).copy(speed = 2f)
        assertEquals(6_000, c.trimmedSourceMs)
        assertEquals(3_000, c.timelineDurationMs)
        assertEquals(2_000, c.sourceTimeAt(0))
        assertEquals(6_000, c.sourceTimeAt(2_000))
        assertEquals(8_000, c.sourceTimeAt(99_999), "clamped to trim end")
        val p = VideoProject(listOf(c, clip(2, 4_000)))
        assertEquals(7_000, p.durationMs)
        assertEquals(listOf(0L, 3_000L), p.clipStarts().toList())
    }

    @Test fun trimIsClamped() {
        val c = clip(1, 5_000)
        assertEquals(0, c.withTrim(-500, 9_999).trimStartMs)
        assertEquals(5_000, c.withTrim(-500, 9_999).trimEndMs)
        val tiny = c.withTrim(3_000, 3_000)
        assertTrue(tiny.trimmedSourceMs >= VideoProject.MIN_CLIP_MS)
        val atEnd = c.withTrim(5_000, 5_000)
        assertTrue(atEnd.trimEndMs <= 5_000 && atEnd.trimmedSourceMs >= VideoProject.MIN_CLIP_MS)
    }

    @Test fun locate() {
        val p = VideoProject(listOf(clip(1, 4_000), clip(2, 6_000).withTrim(1_000, 6_000)))
        assertEquals(VideoProject.Location(0, 1_500, 1_500), p.locate(1_500))
        assertEquals(VideoProject.Location(1, 1_000, 0), p.locate(4_000))
        assertEquals(1, p.locate(999_999)?.clipIndex)
        assertNull(VideoProject().locate(0))
    }

    @Test fun splitAndRemoveKeepTransitionsAligned() {
        var p = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000)))
            .setTransition(0, Transition(TransitionType.FADE_BLACK, 500))
        p = p.splitAt(1_000)
        assertEquals(3, p.clips.size)
        assertEquals(listOf(1_000L, 3_000L, 4_000L), p.clips.map { it.timelineDurationMs })
        assertEquals(TransitionType.NONE, p.transitionAfter(0).type)
        assertEquals(TransitionType.FADE_BLACK, p.transitionAfter(1).type, "original transition follows the second half")
        assertEquals(8_000, p.durationMs, "split never changes duration")
        // Too close to an edge: unchanged
        assertSame(p, p.splitAt(20))
        // Remove the middle clip: transition after it is dropped, so 1->3 keeps slot 0 (cut)
        val r = p.removeClip(1)
        assertEquals(2, r.clips.size)
        assertEquals(1, r.transitions.size)
        assertEquals(TransitionType.NONE, r.transitionAfter(0).type)
        // Remove last clip
        val last = p.removeClip(2)
        assertEquals(1, last.transitions.size)
        assertEquals(VideoProject(), VideoProject(listOf(clip(9, 1000))).removeClip(0).copy(transitions = emptyList()))
        assertSame(p, p.removeClip(7))
    }

    @Test fun moveClip() {
        val p = VideoProject(listOf(clip(1, 1000), clip(2, 1000), clip(3, 1000)))
        assertEquals(listOf(2L, 3L, 1L), p.moveClip(0, 2).clips.map { it.id })
        assertSame(p, p.moveClip(0, 0))
    }

    @Test fun fadesAndTransitions() {
        val p = VideoProject(
            listOf(clip(1, 4_000), clip(2, 4_000)),
            transitions = listOf(Transition(TransitionType.FADE_WHITE, 1_000)),
            fadeIn = Fade(1_000), fadeOut = Fade(2_000),
        )
        assertEquals(1f, FadeCurve.at(p, 0).first, 1e-3f)
        assertEquals(0.5f, FadeCurve.at(p, 500).first, 1e-3f)
        assertEquals(0f, FadeCurve.at(p, 2_000).first, 1e-3f)
        val mid = FadeCurve.at(p, 4_000)
        assertEquals(1f, mid.first, 1e-3f); assertTrue(mid.second, "white transition")
        assertEquals(0.5f, FadeCurve.at(p, 7_000).first, 1e-3f)
        assertEquals(1f, FadeCurve.at(p, 8_000).first, 1e-3f)
    }

    @Test fun audioEnvelope() {
        assertEquals(0f, AudioEnvelope.gain(0, 10_000, 1f, 1_000, 1_000))
        assertEquals(0.5f, AudioEnvelope.gain(500, 10_000, 1f, 1_000, 1_000), 1e-4f)
        assertEquals(0.8f, AudioEnvelope.gain(5_000, 10_000, 0.8f, 1_000, 1_000), 1e-4f)
        assertEquals(0.5f, AudioEnvelope.gain(9_500, 10_000, 1f, 1_000, 1_000), 1e-4f)
        assertEquals(0f, AudioEnvelope.gain(10_001, 10_000, 1f, 0, 0))
    }

    @Test fun textTiming() {
        val t = TimedText(1, "Hi", 1_000, 3_000)
        assertEquals(0f, t.alphaAt(999))
        assertEquals(0.5f, t.alphaAt(1_150), 1e-3f)
        assertEquals(1f, t.alphaAt(2_000))
        assertEquals(0f, t.alphaAt(3_000))
    }

    @Test fun orientation() {
        val c = clip(1, 1000).copy(sourceRotation = 90)
        assertEquals(1080, c.orientedWidth)
        assertEquals(1920, c.copy(quarterTurns = 1).orientedWidth)
    }
}
