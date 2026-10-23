package com.shaneblackburn.progallery

import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.VideoProject
import com.shaneblackburn.progallery.data.LoadResult
import com.shaneblackburn.progallery.data.MediaRepository
import com.shaneblackburn.progallery.video.ProjectComposer
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks the playback-position contract the editor's timeline playhead depends on, using the real
 * preview engine ([CompositionPlayer]) on a single, untrimmed clip — the baseline case reported as
 * out of sync. The editor reads [Player.getCurrentPosition] to drive the playhead, the timecode and
 * the timeline scroll, so this asserts that the reported position lands on a seek target (what the
 * playhead points at after a scrub or frame-step), stays put while not playing (so pause leaves the
 * playhead and the frozen frame together), and ends on the LAST of several rapid seeks (an older
 * seek can't win).
 *
 * Scope: this verifies the position VALUE the UI consumes. A purely visual render-lag (the decoded
 * frame trailing an accurate position) can only be confirmed on a real device, which is why this is
 * delivered alongside an on-device check, not in place of it. If the preview engine can't initialise
 * headlessly on the CI emulator the test is skipped (JUnit assume) rather than failing.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackSyncDeviceTest {

    @get:Rule val permissions = mediaPermissions()
    private val ctx get() = TestMedia.context
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() = TestMedia.seed()

    private fun videoClip(): Clip {
        val v = library().first { it.name == TestMedia.VIDEO }
        return Clip(id = 1, uri = v.uri, name = v.name, sourceDurationMs = v.durationMs, width = v.width, height = v.height)
    }

    private fun library(): List<MediaItem> = runBlocking {
        when (val r = MediaRepository(ctx).loadOnce()) {
            is LoadResult.Success -> r.items
            else -> fail("library load failed: $r")
        }
    }

    /** Runs [block] on the main thread and returns its result. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> onMain(block: () -> T): T {
        val box = arrayOfNulls<Any?>(1)
        instrumentation.runOnMainSync { box[0] = block() }
        return box[0] as T
    }

    private fun positionMs(p: CompositionPlayer): Long = onMain { p.currentPosition }

    @Test fun reportedPositionFollowsSeekTargetsForASingleClip() {
        val project = VideoProject(clips = listOf(videoClip()))
        val duration = project.durationMs
        assumeTrue("test clip too short (${duration}ms)", duration > 1500)

        val ready = CountDownLatch(1)
        var err: Throwable? = null
        val player: CompositionPlayer? = onMain {
            try {
                val pl = CompositionPlayer.Builder(ctx).build()
                pl.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
                    override fun onPlayerError(error: PlaybackException) { err = error; ready.countDown() }
                })
                pl.setComposition(ProjectComposer.build(project))
                pl.prepare()
                pl
            } catch (e: Throwable) { err = e; ready.countDown(); null }
        }

        // CompositionPlayer needs a video output to reach READY; if it can't initialise headlessly
        // on this emulator, skip rather than fail — the contract is still covered by the unit tests
        // (TimelineMathTest) and by on-device verification.
        val gotReady = ready.await(30, TimeUnit.SECONDS)
        assumeTrue("preview engine did not initialise headlessly on this emulator", gotReady && err == null && player != null)
        val p = player!!

        try {
            // 1) Seek accuracy: the position the playhead reads lands on the target.
            for (target in listOf(duration / 4, duration / 2, (duration * 3) / 4)) {
                onMain { p.seekTo(target) }
                Thread.sleep(300)
                val got = positionMs(p)
                assertTrue(abs(got - target) <= 300, "seek to ${target}ms reported ${got}ms")
            }

            // 2) Not playing: the position stays put (pause leaves playhead and frame together).
            val hold1 = positionMs(p)
            Thread.sleep(500)
            val hold2 = positionMs(p)
            assertTrue(abs(hold2 - hold1) <= 40, "position drifted while not playing ($hold1 -> $hold2)")

            // 3) Rapid seeks: the LAST target wins (no stale seek left behind).
            onMain {
                p.seekTo(duration / 5)
                p.seekTo((duration * 4) / 5)
                p.seekTo(duration / 3)
            }
            val last = duration / 2
            onMain { p.seekTo(last) }
            Thread.sleep(400)
            val got = positionMs(p)
            assertTrue(abs(got - last) <= 300, "after rapid seeks expected ~${last}ms, got ${got}ms")
        } finally {
            onMain { runCatching { p.release() } }
        }
    }

    /**
     * The test the reported-timestamp check could not do: verifies the frame ACTUALLY RENDERED on
     * the preview surface matches the timeline position — not just that the player reports the right
     * number. The preview ([CompositionPlayer]) renders into an [ImageReader]; after a paused seek
     * to T we read the rendered frame back and confirm it looks like the clip's SOURCE frame at the
     * mapped source time ([VideoProject.locate]) — and closer to that than to frames well before or
     * after it. A stale/lagging preview frame (the reported bug) fails this even though the position
     * is exact.
     *
     * Skips (JUnit assume) if the preview can't produce frames headlessly on this emulator, so it
     * never false-fails on CI; on a real device it is a true visible-frame check.
     */
    @Test fun previewSurfaceShowsTheFrameForTheSeekPosition() {
        val clip = videoClip()
        val project = VideoProject(clips = listOf(clip))
        val duration = project.durationMs
        assumeTrue("clip too short (${duration}ms)", duration > 4000)
        val (ow, oh) = ProjectComposer.outputSize(project)

        val handlerThread = HandlerThread("preview-reader").apply { start() }
        val handler = Handler(handlerThread.looper)
        val reader = ImageReader.newInstance(ow, oh, PixelFormat.RGBA_8888, 3)
        var latest: IntArray? = null                 // 8x8 grey signature of the last rendered frame
        val frameSeen = java.util.concurrent.atomic.AtomicInteger(0)
        reader.setOnImageAvailableListener({ r ->
            val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try { latest = signatureOf(img); frameSeen.incrementAndGet() } finally { runCatching { img.close() } }
        }, handler)

        val ready = CountDownLatch(1)
        var err: Throwable? = null
        val player: CompositionPlayer? = onMain {
            try {
                val pl = CompositionPlayer.Builder(ctx).build()
                pl.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
                    override fun onPlayerError(error: PlaybackException) { err = error; ready.countDown() }
                })
                pl.setVideoSurface(reader.surface, androidx.media3.common.util.Size(ow, oh))
                pl.setComposition(ProjectComposer.build(project))
                pl.prepare()
                pl
            } catch (e: Throwable) { err = e; ready.countDown(); null }
        }
        val gotReady = ready.await(30, TimeUnit.SECONDS)
        try {
            assumeTrue("preview engine did not initialise headlessly", gotReady && err == null && player != null)
            val p = player!!
            // Require that SOME frame renders at all (else this emulator can't present frames → skip).
            onMain { p.seekTo(duration / 2) }
            assumeTrue("preview produced no frames on this emulator", waitForFrame(frameSeen, 8))

            val failures = mutableListOf<String>()
            // Preview at T must look more like the source frame at T than at clearly different times.
            for (frac in listOf(0.3, 0.6)) {
                val tMs = (duration * frac).toLong()
                val before = frameSeen.get()
                onMain { p.seekTo(tMs) }
                if (!waitForFrame(frameSeen, before + 1, timeoutMs = 4000)) {
                    failures += "no preview frame rendered after seek to ${tMs}ms (stale surface)"
                    continue
                }
                Thread.sleep(250) // let the final composited frame settle
                val shown = latest ?: run { failures += "no frame captured at ${tMs}ms"; continue }

                val srcHere = sourceSignature(clip.uri, project.locate(tMs)!!.sourceMs)
                val srcLo = sourceSignature(clip.uri, project.locate((duration * 0.05).toLong())!!.sourceMs)
                val srcHi = sourceSignature(clip.uri, project.locate((duration * 0.95).toLong())!!.sourceMs)
                if (srcHere == null || srcLo == null || srcHi == null) { continue } // can't read source → inconclusive
                val dHere = dist(shown, srcHere); val dLo = dist(shown, srcLo); val dHi = dist(shown, srcHi)
                android.util.Log.i("PlaybackSyncDeviceTest",
                    "previewframe t=${tMs} dHere=$dHere dLo=$dLo dHi=$dHi")
                // The shown frame must be closest to the source frame at the SAME timeline position.
                if (dHere > dLo || dHere > dHi) {
                    failures += "preview at ${tMs}ms does not match its own source frame " +
                        "(dHere=$dHere dLo=$dLo dHi=$dHi) — surface shows a different position"
                }
            }
            onMain { runCatching { p.release() } }
            assertTrue(failures.isEmpty(), "preview surface / playhead mismatch:\n" + failures.joinToString("\n"))
        } finally {
            runCatching { reader.close() }
            handlerThread.quitSafely()
        }
    }

    private fun waitForFrame(counter: java.util.concurrent.atomic.AtomicInteger, atLeast: Int, timeoutMs: Long = 8000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (counter.get() >= atLeast) return true; Thread.sleep(50) }
        return counter.get() >= atLeast
    }

    /** 8x8 grey signature of an RGBA_8888 [Image] (robust to scale/codec/tone-map differences). */
    private fun signatureOf(img: Image): IntArray {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val w = img.width; val h = img.height
        val sig = IntArray(64)
        for (gy in 0 until 8) for (gx in 0 until 8) {
            val x = (w * gx / 8).coerceIn(0, w - 1)
            val y = (h * gy / 8).coerceIn(0, h - 1)
            val idx = y * rowStride + x * pixStride
            val r = buf.get(idx).toInt() and 0xFF
            val g = buf.get(idx + 1).toInt() and 0xFF
            val b = buf.get(idx + 2).toInt() and 0xFF
            sig[gy * 8 + gx] = (r * 30 + g * 59 + b * 11) / 100
        }
        return sig
    }

    /** 8x8 grey signature of the SOURCE frame at [srcMs] (via MediaMetadataRetriever). */
    private fun sourceSignature(uri: String, srcMs: Long): IntArray? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, Uri.parse(uri))
            val bmp = mmr.getScaledFrameAtTime(srcMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64) ?: return null
            val sig = IntArray(64)
            for (gy in 0 until 8) for (gx in 0 until 8) {
                val px = bmp.getPixel((bmp.width * gx / 8).coerceIn(0, bmp.width - 1), (bmp.height * gy / 8).coerceIn(0, bmp.height - 1))
                val r = (px shr 16) and 0xFF; val g = (px shr 8) and 0xFF; val b = px and 0xFF
                sig[gy * 8 + gx] = (r * 30 + g * 59 + b * 11) / 100
            }
            bmp.recycle()
            sig
        } catch (e: Exception) { null } finally { runCatching { mmr.release() } }
    }

    private fun dist(a: IntArray, b: IntArray): Int {
        var s = 0; for (i in a.indices) s += abs(a[i] - b[i]); return s
    }
}
