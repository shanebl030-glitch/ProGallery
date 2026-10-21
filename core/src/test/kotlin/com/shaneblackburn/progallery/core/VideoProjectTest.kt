package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.video.AudioEnvelope
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Fade
import com.shaneblackburn.progallery.core.video.FadeCurve
import com.shaneblackburn.progallery.core.video.TimedText
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionCurve
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

    @Test fun cutUnwantedMiddleSection() {
        // One 10 s clip; remove the section from 3 s to 7 s, keeping 0–3 s and 7–10 s.
        val base = VideoProject(listOf(clip(1, 10_000)))
        // Split at the start and end of the unwanted section.
        val twoCuts = base.splitAt(3_000).splitAt(7_000)
        assertEquals(3, twoCuts.clips.size)
        assertEquals(listOf(3_000L, 4_000L, 3_000L), twoCuts.clips.map { it.timelineDurationMs })
        // Each piece still maps to the right part of the ORIGINAL source (A/V stays in sync).
        assertEquals(0L to 3_000L, twoCuts.clips[0].trimStartMs to twoCuts.clips[0].trimEndMs)
        assertEquals(3_000L to 7_000L, twoCuts.clips[1].trimStartMs to twoCuts.clips[1].trimEndMs)
        assertEquals(7_000L to 10_000L, twoCuts.clips[2].trimStartMs to twoCuts.clips[2].trimEndMs)
        // Delete the middle; the gap closes and the remaining footage plays continuously.
        val cut = twoCuts.removeClip(1)
        assertEquals(2, cut.clips.size)
        assertEquals(6_000, cut.durationMs, "0–3 s + 7–10 s = 6 s")
        assertEquals(listOf(0L, 3_000L), cut.clipStarts().toList())
        // The frame at timeline 3 s is now the original's 7 s (continuous across the cut).
        assertEquals(VideoProject.Location(1, 7_000, 0), cut.locate(3_000))
    }

    @Test fun trimAfterSplitIsIndependent() {
        val p = VideoProject(listOf(clip(1, 8_000))).splitAt(4_000)
        // Trim only the second piece's start; the first piece is untouched.
        val trimmed = p.updateClip(1) { it.withTrim(5_000, 8_000) }
        assertEquals(0L to 4_000L, trimmed.clips[0].trimStartMs to trimmed.clips[0].trimEndMs)
        assertEquals(5_000L to 8_000L, trimmed.clips[1].trimStartMs to trimmed.clips[1].trimEndMs)
        assertEquals(4_000 + 3_000, trimmed.durationMs)
        // A split never yields a zero- or negative-length piece (both halves >= MIN_CLIP_MS).
        val tiny = VideoProject(listOf(clip(1, 150)))
        assertSame(tiny, tiny.splitAt(50), "50 ms piece is below the minimum clip length")
    }

    @Test fun splitRespectsSpeedWhenMappingSource() {
        // A 2x clip: 10 s of source plays in 5 s of timeline. Split at timeline 2 s.
        val p = VideoProject(listOf(clip(1, 10_000).copy(speed = 2f)))
        val s = p.splitAt(2_000)
        assertEquals(2, s.clips.size)
        // 2 s timeline at 2x = 4 s of source → first piece 0–4 s source, second 4–10 s.
        assertEquals(0L to 4_000L, s.clips[0].trimStartMs to s.clips[0].trimEndMs)
        assertEquals(4_000L to 10_000L, s.clips[1].trimStartMs to s.clips[1].trimEndMs)
        assertEquals(5_000, s.durationMs, "total timeline length unchanged by the split")
    }

    @Test fun frameStepping() {
        // 30 fps clip → one frame = 33 ms; 60 fps → 17 ms; 2x speed halves the step.
        val p30 = VideoProject(listOf(clip(1, 10_000).copy(frameRate = 30)))
        assertEquals(33L, p30.frameDurationMsAt(1_000))
        assertEquals(1_033L, p30.stepFrames(1_000, 1))
        assertEquals(967L, p30.stepFrames(1_000, -1))
        assertEquals(0L, p30.stepFrames(10, -5), "clamped at the start")
        val p60 = VideoProject(listOf(clip(1, 10_000).copy(frameRate = 60)))
        assertEquals(17L, p60.frameDurationMsAt(0))
        val fast = VideoProject(listOf(clip(1, 10_000).copy(frameRate = 30, speed = 2f)))
        assertEquals(17L, fast.frameDurationMsAt(0), "2x speed → half the timeline step")
        // Never past the end.
        assertTrue(p30.stepFrames(p30.durationMs, 10) < p30.durationMs)
    }

    @Test fun blurZoomTransitionCurve() {
        val p = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000), clip(3, 4_000)))
            .setTransition(0, Transition(TransitionType.BLUR, 1_000))     // boundary at 4_000
            .setTransition(1, Transition(TransitionType.ZOOM, 1_000))     // boundary at 8_000
        assertTrue(TransitionCurve.needed(p))
        // Peak blur exactly at the first boundary; zero far away.
        assertEquals(1f, TransitionCurve.at(p, 4_000).blur, 1e-3f)
        assertEquals(0f, TransitionCurve.at(p, 4_000).zoom)
        assertTrue(TransitionCurve.at(p, 4_250).blur in 0.4f..0.6f, "half-way through the taper")
        assertEquals(0f, TransitionCurve.at(p, 2_000).blur, "well before the boundary")
        // Peak zoom at the second boundary.
        assertEquals(1f, TransitionCurve.at(p, 8_000).zoom, 1e-3f)
        // Fades don't register as blur/zoom, and blur/zoom don't darken the frame.
        val fadeProj = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000)))
            .setTransition(0, Transition(TransitionType.FADE_BLACK, 1_000))
        assertTrue(!TransitionCurve.needed(fadeProj))
        assertEquals(0f, com.shaneblackburn.progallery.core.video.FadeCurve.at(p, 4_000).first, "blur boundary is not a brightness dip")
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
