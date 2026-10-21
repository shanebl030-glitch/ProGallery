package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.json.Json
import com.shaneblackburn.progallery.core.video.AspectMode
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Fade
import com.shaneblackburn.progallery.core.video.MusicTrack
import com.shaneblackburn.progallery.core.video.ProjectCodec
import com.shaneblackburn.progallery.core.video.ProjectDoc
import com.shaneblackburn.progallery.core.video.TextAnimation
import com.shaneblackburn.progallery.core.video.TimedText
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.VideoColor
import com.shaneblackburn.progallery.core.video.VideoFilter
import com.shaneblackburn.progallery.core.video.VideoGrade
import com.shaneblackburn.progallery.core.video.VideoProject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectCodecTest {

    // ---------------------------------------------------------------- JSON

    @Test fun jsonRoundTripsTypesAndEscapes() {
        val v = linkedMapOf(
            "s" to "quote\" back\\ slash / tab\t nl\n uni\u0001 emoji 😀",
            "i" to 42, "neg" to -7L, "d" to 1.5, "t" to true, "f" to false, "n" to null,
            "list" to listOf(1, "two", listOf<Any>(), mapOf<String, Any>()),
        )
        @Suppress("UNCHECKED_CAST")
        val back = Json.parse(Json.write(v)) as Map<String, Any?>
        assertEquals(v["s"], back["s"])
        assertEquals(42.0, back["i"])
        assertEquals(-7.0, back["neg"])
        assertEquals(1.5, back["d"])
        assertEquals(true, back["t"]); assertEquals(false, back["f"]); assertNull(back["n"])
        assertEquals(listOf(1.0, "two", emptyList<Any>(), emptyMap<String, Any>()), back["list"])
    }

    @Test fun jsonRejectsMalformedInput() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]x", "{\"a\" 1}", "\"unterminated", "tru", "{} extra")) {
            assertFailsWith<Json.ParseException>(bad) { Json.parse(bad) }
        }
    }

    // ---------------------------------------------------------------- projects

    private fun fullProject(): VideoProject {
        val video = Clip(11, "content://media/external/video/media/5", "beach.mp4", 12_000, 1920, 1080,
            sourceRotation = 90, hasAudio = true, speed = 1.5f, volume = 0.4f, muted = false, quarterTurns = 1,
            flipH = true, color = VideoColor(brightness = 0.1f, exposure = -0.3f, highlights = -0.5f, shadows = 0.6f,
                vibrance = 0.2f, tint = -0.1f, sharpness = 0.5f), filter = VideoFilter.CINEMATIC, filterIntensity = 0.7f, frameRate = 60,
        ).withTrim(1_000, 9_000)
        val photo = Clip(22, "content://media/external/images/media/9", "sunset.jpg", 0, 4000, 3000,
            hasAudio = false, isImage = true, mimeType = "image/jpeg").withImageDuration(4_500)
        return VideoProject(
            clips = listOf(video, photo),
            transitions = listOf(Transition(TransitionType.BLUR, 800)),
            texts = listOf(TimedText(5, "Day \"one\"\nat the beach", 200, 3_000, x = 0.3f, y = 0.2f, size = 0.08f,
                color = 0xFFFF8800.toInt(), background = false, animation = TextAnimation.POP)),
            music = MusicTrack("content://audio/1", "song.mp3", 180_000, startOffsetMs = 15_000, volume = 0.5f,
                fadeInMs = 500, fadeOutMs = 2_000, loop = false),
            fadeIn = Fade(700), fadeOut = Fade(1_200, toWhite = true),
            aspect = AspectMode.PORTRAIT,
        )
    }

    @Test fun projectRoundTripsExactly() {
        val doc = ProjectDoc("p-1", "Beach day ☀", 1_000, 2_000, fullProject(), exportFolder = "Movies/Trips/")
        val back = ProjectCodec.decode(ProjectCodec.encode(doc))
        assertEquals(doc, back)
    }

    @Test fun missingFieldsGetDefaults() {
        // A minimal (older-style) file: only a clip uri + duration.
        val json = """{"id":"x","project":{"clips":[{"uri":"content://v/1","duration":5000}]}}"""
        val doc = ProjectCodec.decode(json)
        assertEquals("Untitled video", doc.name)
        val c = doc.project.clips.single()
        assertEquals(0L, c.trimStartMs); assertEquals(5_000L, c.trimEndMs)
        assertEquals(1f, c.speed); assertEquals(VideoColor(), c.color); assertEquals(VideoFilter.NONE, c.filter)
        assertEquals(AspectMode.ORIGINAL, doc.project.aspect)
        assertNull(doc.project.music)
    }

    @Test fun damagedValuesAreRepairedNotFatal() {
        val json = """{"id":"x","version":3,"futureField":{"a":1},"project":{"aspect":"HEXAGON",
            "clips":[
              {"uri":"content://v/1","duration":5000,"trimStart":-50,"trimEnd":99999,"speed":40,"filter":"PLASMA",
               "color":{"exposure":9,"sharpness":-3}},
              {"uri":"","duration":5000},
              {"uri":"content://v/2","duration":0}
            ],
            "transitions":[{"type":"SPIN","ms":-5}],
            "music":{"uri":"","duration":100}}}"""
        val p = ProjectCodec.decode(json).project
        assertEquals(1, p.clips.size, "clips without a source or duration are dropped")
        val c = p.clips[0]
        assertEquals(0L, c.trimStartMs); assertEquals(5_000L, c.trimEndMs)
        assertEquals(4f, c.speed); assertEquals(VideoFilter.NONE, c.filter)
        assertEquals(1f, c.color.exposure); assertEquals(0f, c.color.sharpness)
        assertEquals(AspectMode.ORIGINAL, p.aspect)
        assertEquals(TransitionType.NONE, p.transitions[0].type)
        assertEquals(0L, p.transitions[0].durationMs)
        assertNull(p.music)
    }

    @Test fun futureMajorVersionIsRefused() {
        assertFailsWith<ProjectCodec.UnsupportedVersionException> { ProjectCodec.decode("""{"version":100}""") }
        assertFailsWith<Json.ParseException> { ProjectCodec.decode("[]") }
    }

    // ---------------------------------------------------------------- photo clips

    @Test fun photoClipDurationAndSplit() {
        val photo = Clip(1, "content://i/1", "a.jpg", 0, 100, 100, isImage = true).withImageDuration(4_000)
        assertEquals(4_000, photo.timelineDurationMs)
        assertEquals(Clip.MIN_IMAGE_MS, photo.withImageDuration(10).timelineDurationMs)
        assertEquals(Clip.MAX_IMAGE_MS, photo.withImageDuration(10_000_000).timelineDurationMs)
        val p = VideoProject(listOf(photo))
        val split = p.splitAt(1_500)
        assertEquals(listOf(1_500L, 2_500L), split.clips.map { it.timelineDurationMs })
        assertTrue(split.clips.all { it.isImage && it.trimStartMs == 0L })
        assertEquals(p, p.splitAt(200), "a piece under the photo minimum isn't created")
    }

    @Test fun dragDropIndex() {
        fun c(id: Long, d: Long) = Clip(id, "content://v/$id", "v", d, 10, 10)
        // Clips: A 0-4s, B 4-6s, C 6-12s (centres 2, 5, 9 s)
        val p = VideoProject(listOf(c(1, 4_000), c(2, 2_000), c(3, 6_000)))
        assertEquals(0, p.dropIndex(0, 0f), "no movement stays put")
        assertEquals(0, p.dropIndex(0, 2_500f), "A's centre 4.5 s is still before B's 5 s")
        assertEquals(1, p.dropIndex(0, 3_500f), "A past B's centre lands after B")
        assertEquals(2, p.dropIndex(0, 8_000f), "A past C's centre lands last")
        assertEquals(0, p.dropIndex(2, -8_000f), "C dragged to the far left lands first")
        assertEquals(1, p.dropIndex(2, -5_000f), "C (centre 4 s) lands between A and B")
        val moved = p.moveClip(0, p.dropIndex(0, 8_000f))
        assertEquals(listOf(2L, 3L, 1L), moved.clips.map { it.id })
        assertEquals(5, p.dropIndex(5, 0f), "out-of-range index unchanged")
    }

    // ---------------------------------------------------------------- grade maths

    private fun grade(c: VideoColor, r: Float, g: Float, b: Float) = VideoGrade.from(c).apply(r, g, b)

    @Test fun neutralGradeIsPassThrough() {
        for (v in listOf(0f, 0.1f, 0.5f, 0.93f, 1f)) {
            val out = grade(VideoColor(), v, v * 0.5f, 1f - v)
            assertTrue(abs(out[0] - v) < 1e-5 && abs(out[1] - v * 0.5f) < 1e-5 && abs(out[2] - (1f - v)) < 1e-5)
        }
        assertTrue(VideoGrade.from(VideoColor()).isIdentity)
    }

    @Test fun gradeControlsMoveTheRightWay() {
        val mid = 0.4f
        assertTrue(grade(VideoColor(exposure = 0.5f), mid, mid, mid)[0] > mid + 0.1f, "exposure up brightens")
        assertTrue(grade(VideoColor(exposure = -0.5f), mid, mid, mid)[0] < mid - 0.1f, "exposure down darkens")
        // Shadows lift dark tones much more than bright ones.
        val darkLift = grade(VideoColor(shadows = 1f), 0.1f, 0.1f, 0.1f)[0] - 0.1f
        val brightLift = grade(VideoColor(shadows = 1f), 0.9f, 0.9f, 0.9f)[0] - 0.9f
        assertTrue(darkLift > 0.15f && brightLift < 0.02f, "shadows: dark +$darkLift bright +$brightLift")
        // Highlights pull bright tones down and leave darks alone.
        val brightPull = 0.9f - grade(VideoColor(highlights = -1f), 0.9f, 0.9f, 0.9f)[0]
        val darkPull = 0.1f - grade(VideoColor(highlights = -1f), 0.1f, 0.1f, 0.1f)[0]
        assertTrue(brightPull > 0.15f && darkPull < 0.02f, "highlights: bright -$brightPull dark -$darkPull")
        // Temperature: warmer = redder and less blue.
        val warm = grade(VideoColor(temperature = 1f), 0.5f, 0.5f, 0.5f)
        assertTrue(warm[0] > 0.55f && warm[2] < 0.45f)
        // Tint: magenta (+) reduces green.
        assertTrue(grade(VideoColor(tint = 1f), 0.5f, 0.5f, 0.5f)[1] < 0.45f)
        // Contrast pushes values away from mid-grey.
        val hc = grade(VideoColor(contrast = 1f), 0.3f, 0.3f, 0.3f)[0]
        assertTrue(hc < 0.25f)
        // Saturation -1 = greyscale.
        val grey = grade(VideoColor(saturation = -1f), 0.8f, 0.2f, 0.1f)
        assertTrue(abs(grey[0] - grey[1]) < 1e-4 && abs(grey[1] - grey[2]) < 1e-4)
        // Vibrance boosts muted colours more than already-saturated ones.
        fun spread(a: FloatArray) = a.max() - a.min()
        val mutedGain = spread(grade(VideoColor(vibrance = 1f), 0.55f, 0.5f, 0.45f)) / 0.1f
        val vividGain = spread(grade(VideoColor(vibrance = 1f), 0.9f, 0.3f, 0.1f)) / 0.8f
        assertTrue(mutedGain > vividGain, "vibrance muted x$mutedGain vivid x$vividGain")
        // Output always clamped.
        val hot = grade(VideoColor(exposure = 1f, brightness = 1f, contrast = 1f), 0.9f, 0.9f, 0.9f)
        assertTrue(hot.all { it in 0f..1f })
    }
}
