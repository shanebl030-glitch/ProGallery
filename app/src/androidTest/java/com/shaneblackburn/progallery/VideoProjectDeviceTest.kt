package com.shaneblackburn.progallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
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
import com.shaneblackburn.progallery.core.video.VideoColor
import com.shaneblackburn.progallery.core.video.VideoFilter
import com.shaneblackburn.progallery.core.video.VideoGrade
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

    private fun photoClip(uri: String, w: Int, h: Int, ms: Long, mime: String = "image/jpeg", color: VideoColor = VideoColor()) =
        Clip(id = System.nanoTime(), uri = uri, name = "photo", sourceDurationMs = ms, width = w, height = h,
            hasAudio = false, isImage = true, mimeType = mime, color = color).withImageDuration(ms)

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
        val project = VideoProject(listOf(videoClip(), photoClip(photo.uri, photo.displayWidth, photo.displayHeight, 3_000)))
        assertEquals(7_000, project.durationMs)
        val e = export(project, "photo_video")
        try {
            assertTrue(abs(e.durationMs - 7_000) <= 400, "duration ${e.durationMs}")
            assertEquals(640, e.width); assertEquals(360, e.height)
            assertTrue(e.hasAudio, "continuous audio track across the silent photo")
            val inPhoto = frame(e, 5_500)
            Shots.save("50_photo_clip_frame", inPhoto)
            // The test photo's sky is blue-ish and bright; the frame must not be black.
            val m = meanRgb(inPhoto, cy = 0.3f)
            assertTrue(m.sum() / 3 > 60, "photo visible in export: ${m.toList()}")
        } finally { e.file.delete() }
    }

    /** Photos are shown upright according to their EXIF orientation (as phones save them). */
    @Test fun exifRotatedPhotoIsUpright() {
        // Stored landscape: left half red, right half blue; EXIF "rotate 90° clockwise".
        // Displayed upright it's portrait with red on top and blue at the bottom.
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            val p = Paint()
            p.color = Color.RED; drawRect(0f, 0f, 200f, 200f, p)
            p.color = Color.BLUE; drawRect(200f, 0f, 400f, 200f, p)
        }
        val uri = TestMedia.insertExtraPhoto("pgtest_rotated.jpg", bmp, ExifInterface.ORIENTATION_ROTATE_90)
        val e = export(VideoProject(listOf(photoClip(uri.toString(), 200, 400, 1_000))), "rotated_photo")
        try {
            assertTrue(e.height > e.width, "portrait output ${e.width}x${e.height}")
            val f = frame(e, 500)
            Shots.save("51_rotated_photo_frame", f)
            val top = meanRgb(f, cy = 0.2f, frac = 0.2f)
            val bottom = meanRgb(f, cy = 0.8f, frac = 0.2f)
            assertTrue(top[0] > 150 && top[2] < 100, "top should be red: ${top.toList()}")
            assertTrue(bottom[2] > 150 && bottom[0] < 100, "bottom should be blue: ${bottom.toList()}")
        } finally { e.file.delete() }
    }

    // ------------------------------------------------------------ colour grade shader

    /** The GPU grade must match the CPU reference maths in core (within codec tolerance). */
    @Test fun gradeShaderMatchesCpuReference() {
        val rgb = intArrayOf(90, 120, 150)
        val uri = TestMedia.insertExtraPhoto("pgtest_solid.png", solid(320, 180, Color.rgb(rgb[0], rgb[1], rgb[2])), png = true).toString()
        val grade = VideoColor(exposure = 0.25f, highlights = -0.4f, shadows = 0.5f, contrast = 0.2f, saturation = 0.3f,
            vibrance = 0.3f, temperature = 0.4f, tint = 0.2f, brightness = 0.05f, sharpness = 0.5f)

        val plain = export(VideoProject(listOf(photoClip(uri, 320, 180, 1_000, "image/png"))), "grade_plain")
        val graded = export(VideoProject(listOf(photoClip(uri, 320, 180, 1_000, "image/png", grade))), "grade_on")
        try {
            val plainMean = meanRgb(frame(plain, 500))
            val gradedFrame = frame(graded, 500)
            Shots.save("52_grade_frame", gradedFrame)
            val gradedMean = meanRgb(gradedFrame)
            // Pipeline sanity: an ungraded export reproduces the colour closely.
            for (i in 0..2) assertTrue(abs(plainMean[i] - rgb[i]) < 8, "plain export ${plainMean.toList()} vs ${rgb.toList()}")
            val ref = VideoGrade.from(grade).apply(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f).map { it * 255.0 }
            android.util.Log.i("VideoProjectDeviceTest", "grade ref=$ref gpu=${gradedMean.toList()} plain=${plainMean.toList()}")
            for (i in 0..2) assertTrue(abs(gradedMean[i] - ref[i]) < 10, "channel $i: GPU ${gradedMean.toList()} vs reference $ref")
            // And the grade really changed the picture.
            assertTrue((0..2).sumOf { abs(gradedMean[it] - plainMean[it]) } > 30, "grade visibly changes the image")
        } finally { plain.file.delete(); graded.file.delete() }
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
