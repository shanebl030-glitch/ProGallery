package com.shaneblackburn.progallery

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.video.Clip
import com.shaneblackburn.progallery.core.video.VideoProject
import com.shaneblackburn.progallery.data.LoadResult
import com.shaneblackburn.progallery.data.MediaRepository
import com.shaneblackburn.progallery.video.ExportState
import com.shaneblackburn.progallery.video.VideoExportSettings
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Regression test for the user-initiated export path through VideoExportManager + the
 * (best-effort) foreground service: tapping Export must render the video and save it, and must
 * NOT depend on the foreground service succeeding. This drives the real manager end to end.
 *
 * Note: the CI emulator runs Android 14, so it cannot reproduce the Android-16-specific
 * foreground-service refusal seen on the Galaxy Z Fold5 — but it does verify the normal export
 * completes and saves, and that the service being best-effort doesn't break it. This test has
 * NOT been run on a physical Samsung device.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ExportManagerDeviceTest {

    @get:Rule val permissions = mediaPermissions()
    private val ctx get() = TestMedia.context
    private val app get() = ApplicationProvider.getApplicationContext<ProGalleryApp>()

    @Before fun setUp() = TestMedia.seed()

    private fun library(): List<MediaItem> = runBlocking {
        when (val r = MediaRepository(ctx).loadOnce()) {
            is LoadResult.Success -> r.items
            else -> fail("library load failed: $r")
        }
    }

    @Test fun userInitiatedExportRendersAndSaves() {
        val v = library().first { it.name == TestMedia.VIDEO }
        val clip = Clip(id = 1, uri = v.uri, name = v.name, sourceDurationMs = v.durationMs, width = v.width, height = v.height)
            .withTrim(0, 1_000)
        val project = VideoProject(listOf(clip))
        val manager = app.videoExport

        // Start exactly as the UI does (from the main thread).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.start(project, VideoExportSettings(), "pgtest_export_mgr", "Movies/PGTest/")
        }

        var saved: Uri? = null
        var failure: String? = null
        val deadline = System.currentTimeMillis() + 240_000
        while (System.currentTimeMillis() < deadline) {
            when (val s = manager.state.value) {
                is ExportState.Done -> { saved = s.uri; break }
                is ExportState.Failed -> { failure = s.message; break }
                else -> Thread.sleep(200)
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { manager.acknowledge() }

        assertTrue(failure == null, "export should not fail: $failure")
        val uri = assertNotNull(saved, "export saved a video to the gallery")
        // The saved file is a real, non-empty video.
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            assertTrue(dur in 500..2_000, "duration $dur ms")
            assertTrue(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
        } finally { runCatching { mmr.release() } }
        // Clean up the exported test file.
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }
}
