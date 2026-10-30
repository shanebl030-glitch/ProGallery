package com.shaneblackburn.progallery

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.Transition
import com.shaneblackburn.progallery.core.video.TransitionType
import com.shaneblackburn.progallery.core.video.VideoProject
import com.shaneblackburn.progallery.data.LoadResult
import com.shaneblackburn.progallery.data.MediaRepository
import com.shaneblackburn.progallery.video.ExoPlayerPreviewEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SPIKE: evaluates the prototype [ExoPlayerPreviewEngine] (ExoPlayer playlist + PREFER_SOFTWARE decode
 * + per-clip setVideoEffects) as a replacement live-preview engine. Runs on device/emulator; CI's
 * emulator uses SOFTWARE decoders, which is exactly the path PREFER_SOFTWARE selects — so these results
 * are representative of the software path, but NOT proof of hardware/vendor behaviour on real phones.
 *
 * The tagged "RESULT ..." log lines are the empirical findings (grep logcat for "SpikeExo").
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExoPlayerPreviewEngineSpikeTest {

    @get:Rule val permissions = mediaPermissions()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instrumentation.targetContext

    private var engine: ExoPlayerPreviewEngine? = null
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null

    @Before fun setUp() = TestMedia.seed()

    @After fun tearDown() {
        instrumentation.runOnMainSync { engine?.release() }
        surface?.release()
        surfaceTexture?.release()
    }

    /** Two clips cut from the SAME source video, with a transition between them. */
    private fun twoClipSameSourceWithTransition(type: TransitionType = TransitionType.FADE_BLACK): VideoProject {
        val lib = runBlocking {
            when (val r = MediaRepository(ctx).loadOnce()) {
                is LoadResult.Success -> r.items
                else -> fail("library load failed: $r")
            }
        }
        val v = lib.first { it.name == TestMedia.VIDEO }
        val clip = Clip(id = 1, uri = v.uri, name = v.name, sourceDurationMs = v.durationMs, width = v.width, height = v.height)
        val base = VideoProject(listOf(clip))
        val mid = (base.durationMs / 2).coerceAtLeast(400)
        return base.splitAt(mid).setTransition(0, Transition(type, minOf(800L, mid)))
    }

    private fun makeSurface(w: Int = 1280, h: Int = 720): Surface {
        val st = SurfaceTexture(false).apply { setDefaultBufferSize(w, h) }
        val s = Surface(st)
        surfaceTexture = st
        surface = s
        return s
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(timeoutMs: Long, pollMs: Long = 100, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onMain(cond)) return true
            Thread.sleep(pollMs)
        }
        return onMain(cond)
    }

    /** The core question: does a software ExoPlayer playlist play continuously across a same-source cut? */
    @Test fun playsContinuouslyAcrossSameSourceBoundary() {
        val project = twoClipSameSourceWithTransition()
        assertTrue(project.clips.size == 2, "need two clips")
        var error: PlaybackException? = null
        val eng = ExoPlayerPreviewEngine(
            ctx, preferSoftware = true,
            onDecoderName = { Log.i(TAG, "video decoder = $it") },
            onError = { error = it; Log.e(TAG, "player error", it) },
        )
        engine = eng
        onMain {
            eng.load(project, 0L, withEffects = false) // isolate playback continuity + decoder
            eng.setSurface(makeSurface())
            eng.play()
        }
        val crossed = waitUntil(30_000) { eng.player?.currentMediaItemIndex == 1 || error != null }
        assertNull(error, "player errored before crossing the boundary: ${error?.errorCodeName}")
        assertTrue(crossed, "playback did not reach the second same-source clip within 30s")
        Log.i(TAG, "RESULT continuity=OK decoder=${eng.videoDecoderName}")
        assertNotNull(eng.videoDecoderName, "no video decoder was initialized")
    }

    /** What decoder is ACTUALLY selected when software is preferred (not assumed). */
    @Test fun reportsActualDecoderWhenSoftwarePreferred() {
        val project = twoClipSameSourceWithTransition()
        val eng = ExoPlayerPreviewEngine(ctx, preferSoftware = true, onDecoderName = { Log.i(TAG, "decoder=$it") })
        engine = eng
        onMain { eng.load(project, 0L, withEffects = false); eng.setSurface(makeSurface()); eng.play() }
        waitUntil(20_000) { eng.videoDecoderName != null }
        val decoder = eng.videoDecoderName
        assertNotNull(decoder, "decoder not initialized")
        val looksSoftware = decoder!!.contains("android", true) || decoder.contains("google", true) || decoder.contains("sw", true)
        // Report, don't hard-fail: software availability is not guaranteed on every device.
        Log.i(TAG, "RESULT preferred=software actual-decoder=$decoder looks-software=$looksSoftware")
    }

    /** Scrubbing/seeking across the clip boundary lands in the right clip. */
    @Test fun seekAcrossClipsLands() {
        val project = twoClipSameSourceWithTransition()
        val eng = ExoPlayerPreviewEngine(ctx, preferSoftware = true)
        engine = eng
        onMain { eng.load(project, 0L, withEffects = false); eng.setSurface(makeSurface()) }
        waitUntil(15_000) { eng.player?.playbackState == Player.STATE_READY }
        onMain { eng.seekToTimeline(project.durationMs - 300) }
        val landed = waitUntil(10_000) { eng.player?.currentMediaItemIndex == 1 }
        Log.i(TAG, "RESULT seek clipIndex=${onMain { eng.player?.currentMediaItemIndex }} pos=${onMain { eng.timelinePositionMs() }}")
        assertTrue(landed, "seek to the second clip did not land in clip index 1")
    }

    /**
     * Best-effort: does ExoPlayer + setVideoEffects (per-clip transition + colour) prepare and run
     * without erroring? Visual correctness of the effects is verified on-device via the debug spike
     * screen (PlayerView gives a sized surface automatically); a raw SurfaceTexture here may not.
     */
    @Test fun playsWithEffectsReachesReady() {
        val project = twoClipSameSourceWithTransition(TransitionType.BLUR)
        var error: PlaybackException? = null
        val eng = ExoPlayerPreviewEngine(
            ctx, preferSoftware = true,
            onDecoderName = { Log.i(TAG, "fx decoder=$it") },
            onError = { error = it; Log.e(TAG, "fx player error", it) },
        )
        engine = eng
        onMain { eng.load(project, 0L, withEffects = true); eng.setSurface(makeSurface()); eng.play() }
        val ready = waitUntil(30_000) { eng.player?.playbackState == Player.STATE_READY || error != null }
        Log.i(TAG, "RESULT effects ready=$ready error=${error?.errorCodeName} decoder=${eng.videoDecoderName}")
        assertNull(error, "effects pipeline errored: ${error?.errorCodeName}")
        assertTrue(ready, "effects pipeline did not reach READY within 30s")
    }

    private companion object {
        const val TAG = "SpikeExo"
    }
}
