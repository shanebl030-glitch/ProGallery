package com.shaneblackburn.progallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
import com.shaneblackburn.progallery.core.edit.Adjustments
import com.shaneblackburn.progallery.core.edit.EditState
import com.shaneblackburn.progallery.core.edit.FilterLook
import com.shaneblackburn.progallery.core.edit.Frame
import com.shaneblackburn.progallery.core.edit.FrameStyle
import com.shaneblackburn.progallery.core.edit.Geometry
import com.shaneblackburn.progallery.core.edit.NormRect
import com.shaneblackburn.progallery.core.edit.Overlay
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.video.AspectMode
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Fade
import com.shaneblackburn.progallery.core.video.MusicTrack
import com.shaneblackburn.progallery.core.video.TimedText
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.VideoColor
import com.shaneblackburn.progallery.core.video.VideoFilter
import com.shaneblackburn.progallery.core.video.VideoProject
import com.shaneblackburn.progallery.data.LoadResult
import com.shaneblackburn.progallery.data.MediaRepository
import com.shaneblackburn.progallery.editor.EditRenderer
import com.shaneblackburn.progallery.editor.EditSaver
import com.shaneblackburn.progallery.editor.ExportOptions
import com.shaneblackburn.progallery.video.ProjectComposer
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the real rendering, saving and export engines on an Android device/emulator.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class EngineDeviceTest {

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

    private fun item(name: String) = library().first { it.name == name }

    // ------------------------------------------------------------ gallery data

    @Test fun repositoryReadsSeededMedia() {
        val items = library()
        val photo = items.first { it.name == TestMedia.PHOTOS[0] }
        assertEquals(1600, photo.width); assertEquals(1200, photo.height)
        assertEquals("image/jpeg", photo.mimeType)
        assertEquals("PGTest", photo.bucketName)
        val video = items.first { it.name == TestMedia.VIDEO }
        assertTrue(video.isVideo)
        assertTrue(abs(video.durationMs - 4000) < 200, "video duration ${video.durationMs}")
    }

    // ------------------------------------------------------------ photo editor engine

    @Test fun photoRenderAndSaveCopyKeepsMetadata() = runBlocking {
        val src = item(TestMedia.PHOTOS[0])
        val full = EditRenderer.decode(ctx.contentResolver, Uri.parse(src.uri), 0)
        assertEquals(1600, full.width)
        val state = EditState(
            geometry = Geometry(quarterTurns = 1, crop = NormRect(0.1f, 0.1f, 0.9f, 0.9f)),
            adjust = Adjustments(exposure = 0.3f, clarity = 0.4f, vignette = 0.3f, sharpness = 0.3f),
            filter = FilterLook.CINEMATIC,
            overlays = listOf(Overlay.Text(1, "Hello", 0.5f, 0.5f), Overlay.Sticker(2, "⭐", 0.2f, 0.2f)),
            frame = Frame(FrameStyle.SOLID, 0.05f),
        )
        val out = EditRenderer.render(full, state)
        // Rotated 1200x1600 → crop 80% → 960x1280 → 5% border (48 px) → 1056x1376.
        assertEquals(1056, out.width); assertEquals(1376, out.height)
        Shots.save("40_engine_photo_render", out)

        val uri = EditSaver(ctx).saveCopy(src, out, ExportOptions())
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        assertEquals(1056, opts.outWidth)
        ctx.contentResolver.openInputStream(uri)!!.use {
            val exif = ExifInterface(it)
            assertEquals("ProGalleryTest", exif.getAttribute(ExifInterface.TAG_MAKE))
            assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1))
            assertEquals("ProGallery", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        }
        assertNotNull(TestMedia.find("pgtest_photo_1_edited.jpg"))
        assertNotNull(TestMedia.find(TestMedia.PHOTOS[0]), "original kept")
        full.recycle(); out.recycle()
    }

    @Test fun photoReplaceOriginalOverwritesInPlace() = runBlocking {
        val src = item(TestMedia.PHOTOS[2])
        val full = EditRenderer.decode(ctx.contentResolver, Uri.parse(src.uri), 0)
        val out = EditRenderer.render(full, EditState(geometry = Geometry(crop = NormRect(0f, 0f, 0.5f, 0.5f)), filter = FilterLook.MONO))
        EditSaver(ctx).replaceOriginal(src, out, ExportOptions())
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(Uri.parse(src.uri))!!.use { BitmapFactory.decodeStream(it, null, opts) }
        assertEquals(800, opts.outWidth); assertEquals(600, opts.outHeight)
    }

    @Test fun previewDecodeIsDownscaled() {
        val src = item(TestMedia.PHOTOS[1])
        val small = EditRenderer.decode(ctx.contentResolver, Uri.parse(src.uri), 800)
        assertEquals(800, maxOf(small.width, small.height))
    }

    private fun threadDump(): String = Thread.getAllStackTraces().entries
        .filter { (t, st) -> st.any { it.className.contains("shaneblackburn") || it.className.contains("stream") || it.className.contains("ForkJoin") } }
        .joinToString("\n") { (t, st) -> "THREAD ${t.name} ${t.state}:\n  " + st.take(25).joinToString("\n  ") }

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(100) }
        val dump = threadDump()
        android.util.Log.e("EngineDeviceTest", "timeout: $what\n$dump")
        fail("Timed out waiting for $what.\n$dump")
    }

    /** The photo editor's live preview must re-render after an edit (diagnoses UI staleness). */
    @Test fun editorViewModelPreviewUpdatesAfterEdit() {
        val app = ctx.applicationContext as android.app.Application
        var vm: com.shaneblackburn.progallery.editor.EditorViewModel? = null
        val photo = item(TestMedia.PHOTOS[0])
        instrumentation.runOnMainSync {
            vm = com.shaneblackburn.progallery.editor.EditorViewModel(app).also { it.open(photo, 1080) }
        }
        waitUntil(30_000, "initial preview") {
            vm!!.load.value is com.shaneblackburn.progallery.editor.EditorLoad.Ready && vm!!.preview.value != null
        }
        val first = vm!!.preview.value
        val t0 = System.currentTimeMillis()
        instrumentation.runOnMainSync { vm!!.commit { it.copy(filter = FilterLook.WARM) } }
        waitUntil(30_000, "preview after Warm filter") { vm!!.preview.value !== first }
        android.util.Log.i("EngineDeviceTest", "preview re-render took ${System.currentTimeMillis() - t0} ms")
        val second = vm!!.preview.value
        instrumentation.runOnMainSync { vm!!.commit { it.copy(geometry = Geometry(crop = NormRect(0f, 0f, 0.5f, 1f))) } }
        waitUntil(30_000, "preview after crop") { vm!!.preview.value !== second }
        val cropped = vm!!.preview.value!!
        assertTrue(cropped.width < cropped.height, "crop applied to preview: ${cropped.width}x${cropped.height}")
    }

    @Test fun filterThumbnailsRender() {
        val photo = item(TestMedia.PHOTOS[1])
        val src = EditRenderer.decode(ctx.contentResolver, Uri.parse(photo.uri), 1080)
        val t0 = System.currentTimeMillis()
        var thumbs: Map<FilterLook, Bitmap>? = null
        val worker = Thread { thumbs = com.shaneblackburn.progallery.editor.FilterThumbnails.render(src) }
        worker.start()
        waitUntil(30_000, "filter thumbnails") { thumbs != null }
        android.util.Log.i("EngineDeviceTest", "thumbnails took ${System.currentTimeMillis() - t0} ms")
        assertEquals(FilterLook.entries.size, thumbs!!.size)
        val mono = thumbs!![FilterLook.MONO]!!
        val p = mono.getPixel(mono.width / 2, mono.height / 2)
        assertTrue(abs(((p shr 16) and 255) - (p and 255)) <= 3, "mono thumbnail is grey")
    }

    // ------------------------------------------------------------ video editor engine

    private fun clip(): Clip {
        val v = item(TestMedia.VIDEO)
        return Clip(id = 1, uri = v.uri, name = v.name, sourceDurationMs = v.durationMs, width = v.width, height = v.height, hasAudio = true)
    }

    private data class Probe(val durationMs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

    private fun export(project: VideoProject, name: String): Probe {
        val out = File(ctx.cacheDir, "$name.mp4").apply { delete() }
        val latch = CountDownLatch(1)
        var error: ExportException? = null
        instrumentation.runOnMainSync {
            Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) = latch.countDown()
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        error = exportException; latch.countDown()
                    }
                })
                .build()
                .start(ProjectComposer.build(project), out.absolutePath)
        }
        assertTrue(latch.await(240, TimeUnit.SECONDS), "export timed out")
        error?.let { throw AssertionError("export failed: ${it.errorCodeName}", it) }
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(out.absolutePath)
            // Save a frame so the result can be inspected.
            mmr.getFrameAtTime(project.durationMs * 500L)?.let { Shots.save("frame_$name", it) }
            return Probe(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong(),
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt(),
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt(),
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } finally { mmr.release(); out.delete() }
    }

    @Test fun videoTrimSpeedFilterTitleFade() {
        val c = clip().withTrim(500, 3500).copy(
            speed = 2f, filter = VideoFilter.MONO, color = VideoColor(brightness = 0.2f, contrast = 0.2f), volume = 0.5f,
        )
        val project = VideoProject(
            clips = listOf(c),
            texts = listOf(TimedText(1, "Hello", 0, 1200)),
            fadeIn = Fade(300),
        )
        assertEquals(1500, project.durationMs)
        val p = export(project, "41_trim_speed")
        assertTrue(abs(p.durationMs - 1500) <= 300, "duration ${p.durationMs}")
        assertEquals(640, p.width); assertEquals(360, p.height)
        assertTrue(p.hasAudio)
    }

    @Test fun videoMergeTransitionMusicAspectRotate() {
        val a = clip()
        val b = clip().copy(id = 2, quarterTurns = 1, flipH = true)
        val project = VideoProject(
            clips = listOf(a, b),
            transitions = listOf(Transition(TransitionType.FADE_BLACK, 600)),
            music = MusicTrack(a.uri, "music", 4000, volume = 0.6f),
            fadeOut = Fade(800, toWhite = true),
            aspect = AspectMode.SQUARE,
        )
        val p = export(project, "42_merge_music_square")
        assertTrue(abs(p.durationMs - 8000) <= 400, "duration ${p.durationMs}")
        assertEquals(p.width, p.height, "square output")
        assertTrue(p.hasAudio)
    }

    @Test fun videoMutedAndSplitClips() {
        val project = VideoProject(listOf(clip().copy(muted = true))).splitAt(2000)
        assertEquals(2, project.clips.size)
        val p = export(project, "43_muted_split")
        assertTrue(abs(p.durationMs - 4000) <= 300, "duration ${p.durationMs}")
    }

    /** Grabs a frame from an export of [project] at [atMs]. */
    private fun exportFrame(project: VideoProject, name: String, atMs: Long): Bitmap {
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
            return mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
        } finally { mmr.release(); out.delete() }
    }

    private fun meanDiff(a: Bitmap, b: Bitmap, y0: Float, y1: Float): Double {
        var sum = 0L; var n = 0
        val w = minOf(a.width, b.width)
        for (y in (a.height * y0).toInt() until (a.height * y1).toInt() step 2) for (x in 0 until w step 2) {
            val p = a.getPixel(x, y); val q = b.getPixel(x, y)
            sum += abs(((p shr 16) and 255) - ((q shr 16) and 255)) + abs(((p shr 8) and 255) - ((q shr 8) and 255)) + abs((p and 255) - (q and 255))
            n++
        }
        return sum.toDouble() / n / 3
    }

    /** Regression: titles must appear during their time range and disappear after it. */
    @Test fun timedTitleAppearsAndDisappears() {
        val base = VideoProject(listOf(clip()))
        val titled = base.copy(texts = listOf(TimedText(7, "TITLE TEST", 0, 1500, size = 0.12f, animation = com.shaneblackburn.progallery.core.video.TextAnimation.NONE)))
        val plainEarly = exportFrame(base, "44a", 700)
        val titledEarly = exportFrame(titled, "44b", 700)
        val plainLate = exportFrame(base, "44c", 3000)
        val titledLate = exportFrame(titled, "44d", 3000)
        Shots.save("44_title_visible", titledEarly)
        Shots.save("45_title_gone", titledLate)
        val early = meanDiff(plainEarly, titledEarly, 0.72f, 0.98f)
        val late = meanDiff(plainLate, titledLate, 0.72f, 0.98f)
        assertTrue(early > 4.0, "title visible while active (diff $early)")
        assertTrue(late < 2.0, "title gone after its end time (diff $late)")
    }

    @Test fun compositionPlayerPreviewPrepares() {
        val project = VideoProject(listOf(clip().copy(filter = VideoFilter.WARM)), texts = listOf(TimedText(1, "Hi", 0, 2000)))
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
        instrumentation.runOnMainSync { player?.release() }
        error?.let { throw AssertionError("preview failed: ${it.errorCodeName}", it) }
        assertTrue(ready, "preview never became ready")
    }

    /** Regression: the editor's preview is rebuilt after every edit; each rebuild must work. */
    @Test fun previewSurvivesRepeatedRebuilds() {
        var ready = CountDownLatch(1)
        var error: String? = null
        var controller: com.shaneblackburn.progallery.video.PreviewController? = null
        instrumentation.runOnMainSync {
            controller = com.shaneblackburn.progallery.video.PreviewController(
                ctx, onError = { if (it != null) error = it }, onReady = { ready.countDown() })
        }
        val projects = listOf(
            VideoProject(listOf(clip())),
            VideoProject(listOf(clip()), fadeIn = Fade(500)),
            VideoProject(listOf(clip().copy(filter = VideoFilter.SEPIA)), texts = listOf(TimedText(1, "Hi", 0, 1500))),
            VideoProject(listOf(clip(), clip().copy(id = 2, speed = 2f))),
        )
        for ((i, p) in projects.withIndex()) {
            ready = CountDownLatch(1)
            var shown = false
            instrumentation.runOnMainSync { shown = controller!!.show(p, 500) }
            assertTrue(shown, "rebuild $i: show() failed: $error")
            assertTrue(ready.await(30, TimeUnit.SECONDS), "rebuild $i never became ready: $error")
            assertEquals(null, error, "rebuild $i error")
        }
        instrumentation.runOnMainSync { controller?.release() }
    }

    // ------------------------------------------------------------ app icon

    @Test fun launcherIconIsAdaptiveWithMonochrome() {
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        assertTrue(icon is AdaptiveIconDrawable, "adaptive icon, got ${icon.javaClass.name}")
        if (Build.VERSION.SDK_INT >= 33) assertNotNull((icon as AdaptiveIconDrawable).monochrome, "themed-icon layer present")
        val bmp = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432)
        icon.draw(Canvas(bmp))
        Shots.save("50_launcher_icon_rendered", bmp)
        // Centre should be the opaque white photo card, corners masked or background.
        val centre = bmp.getPixel(216, 216)
        assertEquals(255, centre ushr 24, "opaque centre")
    }
}
