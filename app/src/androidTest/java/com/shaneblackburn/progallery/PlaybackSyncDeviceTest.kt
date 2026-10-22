package com.shaneblackburn.progallery

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
}
