package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.video.AudioEnvelope
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Fade
import com.shaneblackburn.progallery.core.video.FadeCurve
import com.shaneblackburn.progallery.core.video.TimedText
import com.shaneblackburn.progallery.core.video.ProjectCodec
import com.shaneblackburn.progallery.core.video.ProjectDoc
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.ClipEdge
import com.shaneblackburn.progallery.core.video.TransitionDirection
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

    @Test fun simpleTimelinePositionAddsEarlierClips() {
        // Two speed-1 clips: the player reports position WITHIN the current item, so clip 2's
        // in-item position must have clip 1's timeline duration added back.
        val p = VideoProject(listOf(clip(1, 10_000), clip(2, 4_000)))
        assertEquals(5_000, p.simpleTimelinePosition(0, 5_000), "first clip: position is as-is")
        assertEquals(12_000, p.simpleTimelinePosition(1, 2_000), "second clip: + clip 1 duration")
        assertEquals(0, p.simpleTimelinePosition(0, 0))
    }

    @Test fun simpleTimelinePositionDividesBySpeed() {
        // Clip trimmed to 6 s of source at 2x => 3 s on the timeline. The simple player runs at
        // source rate, so an in-item (source) position of 6 s is 3 s of timeline time.
        val fast = clip(1, 10_000).withTrim(0, 6_000).copy(speed = 2f)
        val p = VideoProject(listOf(fast, clip(2, 4_000)))
        assertEquals(1_500, p.simpleTimelinePosition(0, 3_000), "half the clip, at 2x")
        assertEquals(3_000, p.simpleTimelinePosition(0, 6_000), "end of the clip, at 2x")
        // Clip 2 starts at timeline 3 s (not 6 s), so its in-item position adds 3 s.
        assertEquals(5_000, p.simpleTimelinePosition(1, 2_000))
    }

    @Test fun simpleTimelinePositionClampsIndexAndBounds() {
        val p = VideoProject(listOf(clip(1, 2_000), clip(2, 2_000)))
        assertEquals(4_000, p.simpleTimelinePosition(9, 9_999), "index and position clamped to the project")
        assertEquals(0, p.simpleTimelinePosition(-1, -100))
        assertEquals(0, VideoProject().simpleTimelinePosition(0, 1_000), "empty project")
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

    @Test fun perClipTransitionEdges() {
        // Three 4 s clips: BLUR between 1&2, ZOOM between 2&3, plus a project fade-in.
        val p = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000), clip(3, 4_000)))
            .setTransition(0, Transition(TransitionType.BLUR, 1_000))
            .setTransition(1, Transition(TransitionType.ZOOM, 1_000))
            .copy(fadeIn = Fade(600))
        assertTrue(p.hasAnyTransition())

        // Clip 0: fade-in at its start (peak at t=0), blur-out at its end (peak at t=4000).
        val e0 = p.clipEdge(0)
        assertEquals(TransitionType.FADE_BLACK, e0.inType); assertEquals(600L, e0.inMs)
        assertEquals(TransitionType.BLUR, e0.outType); assertEquals(500L, e0.outMs) // half of 1000
        // Full fade-in at the very start (incoming edge = 1 → fully black).
        val s0 = e0.sampleAt(0)
        assertEquals(TransitionType.FADE_BLACK, s0.type); assertTrue(s0.incoming); assertEquals(1f, s0.edge, 1e-3f)
        assertEquals(1f, e0.uniformsAt(0).fade, 1e-3f)
        assertTrue(!e0.sampleAt(600).isActive)              // fade-in done
        // Full blur at the boundary (clip end); window just begins at t=3500; mid-taper at 3750.
        val b0 = e0.sampleAt(4_000)
        assertEquals(TransitionType.BLUR, b0.type); assertTrue(!b0.incoming); assertEquals(1f, b0.edge, 1e-3f)
        assertTrue(e0.uniformsAt(4_000).blur > 0f)
        assertEquals(0f, e0.sampleAt(3_500).edge, 1e-3f)
        assertTrue(e0.sampleAt(3_750).edge in 0.4f..0.6f)

        // Clip 1: blur-in at its start (SAME boundary, peak at t=0), zoom-out at its end.
        val e1 = p.clipEdge(1)
        assertEquals(TransitionType.BLUR, e1.inType)
        assertEquals(1f, e1.sampleAt(0).edge, 1e-3f)        // boundary blur also peaks on clip 2's first frame
        assertTrue(e1.sampleAt(0).incoming)
        assertEquals(TransitionType.ZOOM, e1.outType)
        val z1 = e1.sampleAt(4_000)
        assertEquals(TransitionType.ZOOM, z1.type); assertEquals(1f, z1.edge, 1e-3f)
        assertTrue(e1.uniformsAt(4_000).scale > 1f, "zoom punches in at the boundary")

        // Clip 2 (last): zoom-in at its start, nothing at its end (no fade-out set).
        val e2 = p.clipEdge(2)
        assertEquals(1f, e2.sampleAt(0).edge, 1e-3f)
        assertEquals(TransitionType.NONE, e2.outType)
        assertTrue(!e2.sampleAt(4_000).isActive)

        // A cross-clip transition (not renderable per-clip) contributes no edge effect.
        val cd = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000)))
            .setTransition(0, Transition(TransitionType.CROSS_DISSOLVE, 1_000))
        assertTrue(!cd.hasAnyTransition())
        assertTrue(!cd.clipEdge(0).sampleAt(4_000).isActive)

        assertTrue(!VideoProject(listOf(clip(1, 4_000))).hasAnyTransition())
    }

    @Test fun transitionDurationSetsExportWindow() {
        // The duration a user picks is split evenly across the cut and drives the effect window
        // in the export (ProjectComposer reads the same clipEdge the preview does).
        fun proj(ms: Long) = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000)))
            .setTransition(0, Transition(TransitionType.BLUR, ms, intensity = 0.8f))

        val short = proj(600).clipEdge(0)
        assertEquals(300L, short.outMs, "half the duration lands on clip A's tail")
        assertTrue(!short.sampleAt(3_699).isActive, "nothing before the window (4000-300=3700)")
        assertTrue(short.sampleAt(3_800).isActive, "effect active inside the window")
        assertEquals(1f, short.sampleAt(4_000).edge, 1e-3f, "peaks at the cut")

        // A longer duration widens the window; intensity flows through to the render uniforms.
        val long = proj(2_000).clipEdge(0)
        assertEquals(1_000L, long.outMs)
        assertTrue(long.sampleAt(3_100).isActive, "wider window starts earlier (4000-1000=3000)")
        assertEquals(0.8f, long.sampleAt(4_000).intensity, 1e-3f)
        // Standard cut carries no duration and no window.
        val cut = VideoProject(listOf(clip(1, 4_000), clip(2, 4_000)))
        assertEquals(0L, cut.clipEdge(0).outMs)
        assertTrue(!cut.clipEdge(0).sampleAt(4_000).isActive)
    }

    @Test fun fullEditingWorkflowStaysConsistent() {
        // Open two clips, split the first and delete the middle piece, trim the second, add a
        // transition with a duration, reorder, frame-step, and round-trip through save/load.
        var p = VideoProject(listOf(clip(1, 6_000), clip(2, 6_000)))
        assertEquals(12_000, p.durationMs)
        p = p.splitAt(2_000)
        assertEquals(3, p.clips.size)
        p = p.removeClip(1) // delete the middle piece of clip 1
        assertEquals(2, p.clips.size)
        assertEquals(8_000, p.durationMs, "0–2 s of A + all of B")
        p = p.updateClip(1) { it.withTrim(1_000, 6_000) }
        assertEquals(7_000, p.durationMs)
        p = p.setTransition(0, Transition(TransitionType.WIPE, 800, TransitionDirection.LEFT, 0.7f))
        assertTrue(p.hasAnyTransition())
        assertEquals(400L, p.clipEdge(0).outMs, "transition duration drives the export window")
        // Reorder (doesn't mutate p here, just checks the op).
        assertEquals(listOf(2L, 1L), p.moveClip(0, 1).clips.map { it.id })
        // Frame-step stays inside the project.
        assertTrue(p.stepFrames(1_000, 1) in 1_001..1_100)
        assertTrue(p.stepFrames(0, -3) == 0L)
        // The whole edit survives save/load byte-for-byte.
        val back = ProjectCodec.decode(ProjectCodec.encode(ProjectDoc("w", "n", 0, 0, p))).project
        assertEquals(p, back)
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

    @Test fun simpleTimelineMapsAcrossClipsAndSpeed() {
        // clip1: 4s @1x (timeline 4000); clip2: 10s @2x (timeline 5000). Total 9000.
        val p = VideoProject(listOf(clip(1, 4_000), clip(2, 10_000).copy(speed = 2f)))
        assertEquals(9_000, p.durationMs)

        // Timeline -> (clipIndex, in-clip source offset).
        assertEquals(0 to 2_000L, p.simpleSeek(2_000))          // inside clip 1 (speed 1)
        assertEquals(1 to 0L, p.simpleSeek(4_000))              // the boundary -> start of clip 2
        assertEquals(1 to 2_000L, p.simpleSeek(5_000))          // 1000ms into clip2 timeline @2x -> 2000ms source

        // Inverse: player-reported (index, in-clip position) -> whole-timeline position.
        assertEquals(2_000, p.simpleTimelinePosition(0, 2_000))
        assertEquals(4_000, p.simpleTimelinePosition(1, 0))     // start of clip 2 is timeline 4000, not 0
        assertEquals(5_000, p.simpleTimelinePosition(1, 2_000)) // 2000ms source @2x -> +1000ms timeline
    }

    @Test fun simpleTimelineRoundTrips() {
        val p = VideoProject(listOf(clip(1, 3_000), clip(2, 8_000).copy(speed = 0.5f), clip(3, 5_000).copy(speed = 2f)))
        for (t in longArrayOf(0, 1_000, 2_999, 3_000, 10_000, 19_000, p.durationMs - 1)) {
            val (i, off) = p.simpleSeek(t)
            val back = p.simpleTimelinePosition(i, off)
            // Exact apart from <=1ms of integer-rounding on fractional speeds (far sub-frame).
            assertTrue(kotlin.math.abs(back - t) <= 1, "round-trip at $t got $back")
        }
    }
}
