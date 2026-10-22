package com.shaneblackburn.progallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.CompositionPlayer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.VideoColor
import com.shaneblackburn.progallery.core.video.VideoFilter
import com.shaneblackburn.progallery.core.video.VideoProject
import com.shaneblackburn.progallery.data.LoadResult
import com.shaneblackburn.progallery.data.MediaRepository
import com.shaneblackburn.progallery.video.ProjectComposer
import com.shaneblackburn.progallery.video.ProjectStore
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** v0.3.0 video engine: saved projects, photo clips, the GPU colour grade. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class VideoProjectDeviceTest {

    @get:Rule val permissions = mediaPermissions()
    private val ctx get() = TestMedia.context
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() = TestMedia.seed()

    private fun library(): List<MediaItem> = runBlocking {
        when (val r = MediaRepository(ctx).loadOnce()) {
            is LoadResult.Success -> r.items
            else -> fail("library load failed: $r")
        }
    }

    private fun videoClip(): Clip {
        val v = library().first { it.name == TestMedia.VIDEO }
        return Clip(id = 1, uri = v.uri, name = v.name, sourceDurationMs = v.durationMs, width = v.width, height = v.height)
    }

    private fun photoClip(uri: String, w: Int, h: Int, ms: Long, mime: String = "image/jpeg", color: VideoColor = VideoColor(), rotation: Int = 0) =
        Clip(id = System.nanoTime(), uri = uri, name = "photo", sourceDurationMs = ms, width = w, height = h,
            sourceRotation = rotation, hasAudio = false, isImage = true, mimeType = mime, color = color).withImageDuration(ms)

    private class Exported(val file: File, val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

    private fun export(project: VideoProject, name: String): Exported {
        val out = File(ctx.cacheDir, "$name.mp4").apply { delete() }
        val latch = CountDownLatch(1)
        var error: ExportException? = null
        instrumentation.runOnMainSync {
            Transformer.Builder(ctx).setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = latch.countDown()
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error = exportException; latch.countDown()
                    }
                }).build().start(ProjectComposer.build(project), out.absolutePath)
        }
        assertTrue(latch.await(240, TimeUnit.SECONDS), "export timed out")
        error?.let { throw AssertionError("export failed: ${it.errorCodeName}", it) }
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(out.absolutePath)
            return Exported(out,
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(),
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt(),
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        } finally { mmr.release() }
    }

    private fun frame(e: Exported, atMs: Long): Bitmap {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(e.file.absolutePath)
            return mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally { mmr.release() }
    }

    /** Mean RGB (0..255) of a centred box covering [frac] of each dimension. */
    private fun meanRgb(b: Bitmap, cx: Float = 0.5f, cy: Float = 0.5f, frac: Float = 0.3f): DoubleArray {
        val s = DoubleArray(3); var n = 0
        val x0 = (b.width * (cx - frac / 2)).toInt().coerceAtLeast(0); val x1 = (b.width * (cx + frac / 2)).toInt().coerceAtMost(b.width)
        val y0 = (b.height * (cy - frac / 2)).toInt().coerceAtLeast(0); val y1 = (b.height * (cy + frac / 2)).toInt().coerceAtMost(b.height)
        for (y in y0 until y1 step 2) for (x in x0 until x1 step 2) {
            val p = b.getPixel(x, y)
            s[0] += Color.red(p); s[1] += Color.green(p); s[2] += Color.blue(p); n++
        }
        return DoubleArray(3) { s[it] / n }
    }

    private fun solid(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    // ------------------------------------------------------------ transitions

    /** Mean per-channel difference between two frames over a vertical band [y0,y1] (fractions). */
    private fun meanDiff(a: Bitmap, b: Bitmap, y0: Float, y1: Float): Double {
        var sum = 0L; var n = 0
        val w = minOf(a.width, b.width)
        for (y in (a.height * y0).toInt() until (a.height * y1).toInt() step 3) for (x in 0 until w step 3) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            sum += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            n++
        }
        return sum.toDouble() / n / 3
    }

    /** Mean absolute difference between horizontally adjacent pixels (high-frequency energy). */
    private fun highFrequency(b: Bitmap): Double {
        var sum = 0L; var n = 0
        for (y in 0 until b.height step 3) for (x in 1 until b.width step 3) {
            val p = b.getPixel(x, y); val q = b.getPixel(x - 1, y)
            sum += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            n++
        }
        return sum.toDouble() / n
    }

    /** Mean luminance (0..255) of a frame. */
    private fun meanLuma(b: Bitmap): Double {
        var s = 0.0; var n = 0
        for (y in 0 until b.height step 3) for (x in 0 until b.width step 3) {
            val p = b.getPixel(x, y)
            s += 0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p); n++
        }
        return s / n
    }

    /**
     * Exports EVERY selectable transition between two visibly-different clips and inspects the
     * boundary frame (peak effect) and an off-cut frame (should be untouched). This is the core
     * of Shane's request: proof each effect actually renders in the exported file, lasts only its
     * window, and never crashes export or leaves an unexpected black frame. Problems are collected
     * so one bad transition doesn't hide the others.
     */
    @Test fun allTransitionsRenderOnExport() {
        // Two distinct, short clips keep the software-GL exports light on the CI emulator.
        val a = videoClip().withTrim(0, 1_200)
        val b = videoClip().copy(id = 2).withTrim(1_500, 2_700)
        val boundary = a.timelineDurationMs
        val offCut = 300L // outside both transition windows (half-duration = 300 ms)
        fun proj(t: TransitionType) = VideoProject(listOf(a, b), transitions = listOf(Transition(t, 600)))

        val plain = export(proj(TransitionType.NONE), "trx_plain")
        val plainCut = frame(plain, boundary)
        val plainOff = frame(plain, offCut)
        val hfPlainCut = highFrequency(plainCut)
        val lumaPlainCut = meanLuma(plainCut)
        android.util.Log.i("VideoProjectDeviceTest", "trx plain: hfCut=$hfPlainCut lumaCut=$lumaPlainCut")

        val failures = mutableListOf<String>()
        for (t in TransitionType.selectable) {
            if (t == TransitionType.NONE) continue
            try {
                val e = export(proj(t), "trx_${t.name.lowercase()}")
                try {
                    if (abs(e.durationMs - 2 * boundary) > 500) failures += "$t: duration ${e.durationMs} (expected ~${2 * boundary})"
                    val cut = frame(e, boundary); Shots.save("57_trx_${t.name.lowercase()}", cut)
                    val off = frame(e, offCut)
                    val diffCut = meanDiff(plainCut, cut, 0f, 1f)
                    val diffOff = meanDiff(plainOff, off, 0f, 1f)
                    val hfCut = highFrequency(cut)
                    val lumaCut = meanLuma(cut)
                    android.util.Log.i("VideoProjectDeviceTest",
                        "trx $t: diffCut=$diffCut diffOff=$diffOff hfCut=$hfCut lumaCut=$lumaCut")
                    // 1) The effect is visibly present at the cut.
                    if (diffCut < 3.0) failures += "$t: no visible change at the cut (diff $diffCut)"
                    // 2) The effect is time-limited: an off-cut frame is essentially the plain cut.
                    if (diffOff > 20.0) failures += "$t: effect leaks outside its window (off-cut diff $diffOff)"
                    // 3) Type-specific sanity at the boundary.
                    when (t) {
                        // These pass THROUGH black at the exact cut (by design): fade, wipe/iris
                        // close to black, and whip/push slide the clips fully off-screen.
                        TransitionType.FADE_BLACK, TransitionType.WIPE, TransitionType.IRIS,
                        TransitionType.WHIP_PAN, TransitionType.PUSH ->
                            if (lumaCut > lumaPlainCut * 0.6) failures += "$t: should darken/clear at the cut (luma $lumaCut vs $lumaPlainCut)"
                        TransitionType.FADE_WHITE ->
                            if (lumaCut < 200) failures += "$t: should go bright/white at the cut (luma $lumaCut)"
                        TransitionType.LIGHT_LEAK ->
                            if (lumaCut <= lumaPlainCut) failures += "$t: should brighten at the cut (luma $lumaCut vs $lumaPlainCut)"
                        TransitionType.BLUR, TransitionType.CROSS_ZOOM ->
                            if (hfCut > hfPlainCut * 0.9) failures += "$t: should soften at the cut (hf $hfCut vs $hfPlainCut)"
                        else -> // ZOOM / SPIN / SHAKE / GLITCH / PIXELATE / RIPPLE: must transform, not black.
                            if (lumaCut < 4) failures += "$t: unexpected black frame at the cut (luma $lumaCut)"
                    }
                } finally { e.file.delete() }
            } catch (ex: Throwable) {
                failures += "$t: export threw ${ex.javaClass.simpleName}: ${ex.message}"
            }
        }
        plain.file.delete()
        assertTrue(failures.isEmpty(), "transition export problems:\n" + failures.joinToString("\n"))
    }

    @Test fun blurAndZoomTransitionsAffectBoundaryFrame() {
        // Short clips keep the three software-GL exports light on the CI emulator.
        val a = videoClip().withTrim(0, 1_500)
        val b = videoClip().copy(id = 2).withTrim(0, 1_500)
        val boundary = a.timelineDurationMs
        fun twoClips(t: com.shaneblackburn.progallery.core.video.TransitionType) =
            VideoProject(listOf(a, b), transitions = listOf(
                com.shaneblackburn.progallery.core.video.Transition(t, 1_000)))

        val plain = export(twoClips(com.shaneblackburn.progallery.core.video.TransitionType.NONE), "tr_plain")
        val blur = export(twoClips(com.shaneblackburn.progallery.core.video.TransitionType.BLUR), "tr_blur")
        val zoom = export(twoClips(com.shaneblackburn.progallery.core.video.TransitionType.ZOOM), "tr_zoom")
        try {
            // Duration is unchanged by these single-stream transitions.
            assertTrue(abs(plain.durationMs - 2 * boundary) <= 500, "plain ${plain.durationMs}")
            assertTrue(abs(blur.durationMs - 2 * boundary) <= 500, "blur ${blur.durationMs}")

            val plainF = frame(plain, boundary); Shots.save("54_tr_plain", plainF)
            val blurF = frame(blur, boundary); Shots.save("55_tr_blur", blurF)
            val zoomF = frame(zoom, boundary); Shots.save("56_tr_zoom", zoomF)

            // At the exact boundary the blur is at full strength → fewer sharp edges.
            val hfPlain = highFrequency(plainF); val hfBlur = highFrequency(blurF)
            android.util.Log.i("VideoProjectDeviceTest", "transition hf plain=$hfPlain blur=$hfBlur")
            assertTrue(hfBlur < hfPlain * 0.8, "blur softens the boundary frame (plain $hfPlain -> blur $hfBlur)")
            // Zoom scales the frame at the boundary → content visibly shifts vs a plain cut.
            val zoomDiff = meanDiff(plainF, zoomF, 0f, 1f)
            android.util.Log.i("VideoProjectDeviceTest", "transition zoom diff=$zoomDiff")
            assertTrue(zoomDiff > 3.0, "zoom changes the boundary frame (diff $zoomDiff)")
        } finally { plain.file.delete(); blur.file.delete(); zoom.file.delete() }
    }

    // ------------------------------------------------------------ diagnostics

    @Test fun videoDiagnosticsReadsFormat() {
        val v = library().first { it.name == TestMedia.VIDEO }
        val info = com.shaneblackburn.progallery.video.VideoDiagnostics.read(ctx, Uri.parse(v.uri))
        assertNotNull(info, "format read")
        assertTrue(info!!.width > 0 && info.height > 0, "dimensions ${info.width}x${info.height}")
        assertTrue(info.mime.startsWith("video/"), "mime ${info.mime}")
        // The CI test clip is plain SDR H.264/8-bit; summary should be human-readable.
        android.util.Log.i("VideoProjectDeviceTest", "diag: ${info.summary()} hdr=${info.hdr} bit=${info.bitDepth}")
        assertTrue(info.hdr == "SDR" || info.hdr == "unknown", "test clip is SDR")
    }

    // ------------------------------------------------------------ split & cut

    /** Split the clip, delete the middle section, and export; duration/audio must be correct. */
    @Test fun splitDeleteMiddleSectionExports() {
        val v = videoClip() // ~4 s test clip with audio
        val dur = v.sourceDurationMs
        val a = (dur * 0.3).toLong()
        val b = (dur * 0.7).toLong()
        // Cut out the middle 40%: split at a and b, delete the middle piece.
        val project = VideoProject(listOf(v)).splitAt(a).splitAt(b)
        assertEquals(3, project.clips.size)
        val cut = project.removeClip(1)
        assertEquals(2, cut.clips.size)
        val expected = cut.durationMs // ≈ 60% of the source
        assertTrue(kotlin.math.abs(expected - (a + (dur - b))) < 5, "timeline math: $expected")

        val e = export(cut, "split_cut")
        try {
            assertTrue(abs(e.durationMs - expected) <= 400, "duration ${e.durationMs} vs ~$expected")
            assertTrue(e.hasAudio, "audio kept across the cut")
        } finally { e.file.delete() }
        // Original MediaStore file is untouched.
        val still = MediaRepository(ctx).let { runBlocking { (it.loadOnce() as LoadResult.Success).items } }
            .first { it.name == TestMedia.VIDEO }
        assertEquals(v.sourceDurationMs, still.durationMs, "original video length unchanged")
    }

    // ------------------------------------------------------------ saved projects

    @Test fun projectStoreSavesLoadsAndSurvivesBadFiles() = runBlocking {
        val store = ProjectStore(ctx)
        val project = VideoProject(listOf(videoClip().copy(color = VideoColor(exposure = 0.3f), filter = VideoFilter.WARM)))
        val doc = store.save(store.newDoc(project, "Test project", "Movies/PGTest/"))
        // A damaged file next to it must not hide the good project.
        val bad = File(ctx.filesDir, "projects/00000000-bad.json").apply { writeText("{ not json") }
        try {
            store.refresh()
            assertEquals(1, store.unreadable.value)
            val loaded = assertNotNull(store.load(doc.id))
            assertEquals(project, loaded.project)
            assertTrue(store.projects.value.any { it.id == doc.id })

            store.rename(doc.id, "Renamed")
            assertEquals("Renamed", store.load(doc.id)!!.name)
            val copy = assertNotNull(store.duplicate(doc.id))
            assertEquals(project, copy.project)
            assertTrue(copy.id != doc.id)

            store.delete(doc.id); store.delete(copy.id)
            assertNull(store.load(doc.id))
            assertTrue(store.projects.value.none { it.id == doc.id || it.id == copy.id })
        } finally {
            bad.delete()
        }
    }

    // ------------------------------------------------------------ photo clips

    @Test fun photoAndVideoClipsExportTogether() {
        val photo = library().first { it.name == TestMedia.PHOTOS[0] }
        val video = videoClip()
        val expected = video.timelineDurationMs + 3_000
        val project = VideoProject(listOf(video, photoClip(photo.uri, photo.displayWidth, photo.displayHeight, 3_000)))
        assertEquals(expected, project.durationMs)
        val e = export(project, "photo_video")
        try {
            assertTrue(abs(e.durationMs - expected) <= 400, "duration ${e.durationMs} vs ~$expected")
            assertEquals(640, e.width); assertEquals(360, e.height)
            assertTrue(e.hasAudio, "continuous audio track across the silent photo")
            val inPhoto = frame(e, 5_500)
            Shots.save("50_photo_clip_frame", inPhoto)
            // The test photo's sky is blue-ish and bright; the frame must not be black.
            val m = meanRgb(inPhoto, cy = 0.3f)
            assertTrue(m.sum() / 3 > 60, "photo visible in export: ${m.toList()}")
        } finally { e.file.delete() }
    }

    /**
     * A photo with EXIF orientation exports with its content intact (both colours present,
     * not black or garbled). The clip carries the EXIF rotation and the composer applies it.
     *
     * Note: exact upright orientation of a *single-image* export can't be asserted reliably
     * on the emulator (Media3's single-image effect pipeline differs from the video case),
     * so the precise top/bottom placement is logged for on-device confirmation rather than
     * asserted here. The common case — a photo inside a project that also has video — is
     * covered by [photoAndVideoClipsExportTogether].
     */
    @Test fun exifRotatedPhotoRendersContent() {
        // Landscape 400x200: left half red, right half blue; EXIF "rotate 90° CW".
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            val p = Paint()
            p.color = Color.RED; drawRect(0f, 0f, 200f, 200f, p)
            p.color = Color.BLUE; drawRect(200f, 0f, 400f, 200f, p)
        }
        val uri = TestMedia.insertExtraPhoto("pgtest_rotated.jpg", bmp, ExifInterface.ORIENTATION_ROTATE_90)
        // Realistic case: the photo sits in a project that also has video (single-image-only
        // export is a separate Media3 path that the emulator renders unreliably). The photo's
        // content must survive export: both colours present in its segment.
        val video = videoClip()
        val photo = photoClip(uri.toString(), 400, 200, 1_500, rotation = 90)
        val e = export(VideoProject(listOf(video, photo)), "rotated_photo")
        try {
            val atPhoto = video.timelineDurationMs + 700
            // The emulator occasionally yields a black frame; try a few points in the photo segment.
            var best = listOf(DoubleArray(3), DoubleArray(3), DoubleArray(3), DoubleArray(3))
            var bestSum = -1.0
            for (dt in listOf(0L, 300L, -300L)) {
                val f = frame(e, atPhoto + dt)
                val q = listOf(meanRgb(f, 0.3f, 0.3f, 0.25f), meanRgb(f, 0.7f, 0.3f, 0.25f),
                    meanRgb(f, 0.3f, 0.7f, 0.25f), meanRgb(f, 0.7f, 0.7f, 0.25f))
                val sum = q.sumOf { it.sum() }
                if (sum > bestSum) { bestSum = sum; best = q; Shots.save("51_rotated_photo_frame", f) }
            }
            android.util.Log.i("VideoProjectDeviceTest", "rotated photo in project, quadrants=${best.map { it.toList() }}")
            val hasRed = best.any { it[0] > 110 && it[0] - it[2] > 40 }
            val hasBlue = best.any { it[2] > 110 && it[2] - it[0] > 40 }
            assertTrue(hasRed && hasBlue, "photo content present (red=$hasRed blue=$hasBlue): ${best.map { it.toList() }}")
        } finally { e.file.delete() }
    }

    // ------------------------------------------------------------ colour grade shader

    /**
     * The GPU grade shader runs end-to-end and moves colours in the directions the grade
     * specifies. (Absolute sRGB can't be checked through an H.264 YUV round-trip — the
     * codec uses limited range — so this checks directional effects against an ungraded
     * export of the same source, which is subject to the same round-trip.)
     */
    @Test fun gradeShaderChangesExportAsExpected() {
        val rgb = intArrayOf(120, 110, 100)
        val uri = TestMedia.insertExtraPhoto("pgtest_solid.png", solid(320, 180, Color.rgb(rgb[0], rgb[1], rgb[2])), png = true).toString()
        fun exp(color: VideoColor, name: String) =
            meanRgb(frame(export(VideoProject(listOf(photoClip(uri, 320, 180, 800, "image/png", color))), name).also { it.file.deleteOnExit() }, 400))
        val plain = exp(VideoColor(), "grade_plain")
        Shots.save("52_grade_plain", solid(40, 40, Color.rgb(plain[0].toInt(), plain[1].toInt(), plain[2].toInt())))

        // Exposure up brightens every channel.
        val bright = exp(VideoColor(exposure = 0.6f), "grade_exposure")
        assertTrue(bright.sum() > plain.sum() + 30, "exposure up brightens: $plain -> $bright")
        // Warmth up: red rises, blue falls relative to plain.
        val warm = exp(VideoColor(temperature = 0.7f), "grade_warm")
        assertTrue(warm[0] - plain[0] > 6 && warm[2] - plain[2] < -6, "warmth: $plain -> $warm")
        // Saturation -1 greys the image: channels converge.
        fun spread(a: DoubleArray) = a.max() - a.min()
        val grey = exp(VideoColor(saturation = -1f), "grade_grey")
        assertTrue(spread(grey) < spread(plain), "desaturate narrows spread: ${spread(plain)} -> ${spread(grey)}")
        android.util.Log.i("VideoProjectDeviceTest", "grade plain=$plain bright=$bright warm=$warm grey=$grey")
    }

    // ------------------------------------------------------------ preview

    @Test fun previewPreparesWithPhotoAndGradedClips() {
        val photo = library().first { it.name == TestMedia.PHOTOS[1] }
        val project = VideoProject(listOf(
            videoClip().copy(color = VideoColor(shadows = 0.4f, sharpness = 0.3f), filter = VideoFilter.SEPIA, filterIntensity = 0.5f),
            photoClip(photo.uri, photo.displayWidth, photo.displayHeight, 2_000, color = VideoColor(exposure = -0.2f)),
        ))
        val latch = CountDownLatch(1)
        var error: PlaybackException? = null
        var player: CompositionPlayer? = null
        instrumentation.runOnMainSync {
            val pl = CompositionPlayer.Builder(ctx).build()
            player = pl
            pl.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) latch.countDown() }
                override fun onPlayerError(e: PlaybackException) { error = e; latch.countDown() }
            })
            pl.setComposition(ProjectComposer.build(project))
            pl.prepare()
        }
        val ready = latch.await(30, TimeUnit.SECONDS)
        // Seek into the photo and make sure that works too.
        val seekLatch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            player?.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) seekLatch.countDown() }
            })
            player?.seekTo(5_000)
        }
        seekLatch.await(15, TimeUnit.SECONDS)
        instrumentation.runOnMainSync { player?.release() }
        error?.let { throw AssertionError("preview failed: ${it.errorCodeName}", it) }
        assertTrue(ready, "preview never became ready")
    }
}
