package com.shaneblackburn.progallery

import android.graphics.SurfaceTexture
import android.view.Surface
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
 * preview engine ([CompositionPlayer]) on a single, untrimmed clip — the baseline case the user
 * reported as out of sync. The editor reads [Player.getCurrentPosition] to drive the playhead,
 * timecode and timeline scroll, so these assert that the reported position:
 *   - lands on a seek target (what the playhead points at after a scrub/frame-step),
 *   - is stable while paused (so pause leaves the playhead and the frozen frame together),
 *   - advances while playing,
 *   - ends on the LAST of several rapid seeks (an older seek can't win).
 *
 * Scope note: this verifies the position VALUE the UI consumes. A purely visual render-lag (the
 * decoded frame trailing an accurate position) can only be confirmed on a real device, which is
 * why this is delivered alongside an on-device check, not in place of it.
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

    private fun <T> onMain(block: () -> T): T {
        val box = arrayOfNulls<Any?>(1)
        instrumentation.runOnMainSync { box[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    private fun pos(p: Player): Long = onMain { p.currentPosition }

    @Test fun positionTracksSeekPlayAndPauseForASingleClip() {
        val clip = videoClip()
        val project = VideoProject(clips = listOf(clip))
        val duration = project.durationMs
        assertTrue(duration > 1500, "test clip should be long enough to seek within (was ${duration}ms)")

        val ready = CountDownLatch(1)
        var buildError: Throwable? = null
        var surface: Surface? = null
        var texture: SurfaceTexture? = null

        val player = onMain {
            try {
                val pl = CompositionPlayer.Builder(ctx).build()
                // A real output surface so playback actually advances (no on-screen view needed).
                val st = SurfaceTexture(false).apply { setDefaultBufferSize(clip.width.coerceAtLeast(2), clip.height.coerceAtLeast(2)) }
                val sf = Surface(st)
                texture = st; surface = sf
                pl.setVideoSurface(sf)
                pl.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { buildError = error; ready.countDown() }
                })
                pl.setComposition(ProjectComposer.build(project))
                pl.prepare()
                pl
            } catch (e: Throwable) { buildError = e; ready.countDown(); null }
        }
        assertTrue(ready.await(60, TimeUnit.SECONDS), "preview player never became ready")
        buildError?.let { fail("preview player failed to prepare: ${it.message}") }
        val p = player ?: fail("no player")

        try {
            // 1) Seek accuracy: the position the playhead would read lands on the target.
            val mid = duration / 2
            onMain { p.seekTo(mid) }
            Thread.sleep(400)
            val afterSeek = pos(p)
            assertTrue(abs(afterSeek - mid) <= 250, "seek to ${mid}ms reported ${afterSeek}ms")

            // 2) Play advances the position.
            onMain { p.play() }
            Thread.sleep(700)
            val playing1 = pos(p)
            Thread.sleep(300)
            val playing2 = pos(p)
            assertTrue(playing1 > mid, "playback should advance past the seek point (was ${playing1}ms vs ${mid}ms)")
            assertTrue(playing2 >= playing1, "playback position must be monotonic (${playing1} -> ${playing2})")
            assertTrue(playing2 <= duration + 100, "position must stay within the clip (${playing2} vs ${duration})")

            // 3) Pause freezes the position: the playhead and the shown frame stop together.
            onMain { p.pause() }
            Thread.sleep(150)
            val paused1 = pos(p)
            Thread.sleep(500)
            val paused2 = pos(p)
            assertTrue(abs(paused2 - paused1) <= 40, "paused position drifted ($paused1 -> $paused2)")

            // 4) Rapid seeks: the final target wins (no stale seek left behind).
            onMain {
                p.seekTo((duration * 0.2).toLong())
                p.seekTo((duration * 0.7).toLong())
                p.seekTo((duration * 0.35).toLong())
            }
            val finalTarget = (duration * 0.5).toLong()
            onMain { p.seekTo(finalTarget) }
            Thread.sleep(500)
            val afterRapid = pos(p)
            assertTrue(abs(afterRapid - finalTarget) <= 250, "after rapid seeks position should be the last target ${finalTarget}ms, was ${afterRapid}ms")
        } finally {
            onMain { runCatching { p.release() } }
            surface?.release()
            texture?.release()
        }
    }
}
