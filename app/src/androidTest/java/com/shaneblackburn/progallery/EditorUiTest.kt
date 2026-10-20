package com.shaneblackburn.progallery

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Drives the photo and video editors through the UI and checks the saved output files. */
@RunWith(AndroidJUnit4::class)
class EditorUiTest {

    @get:Rule val permissions = mediaPermissions()
    private val ui = Ui()

    @Before fun setUp() {
        TestMedia.seed()
        ui.launch()
    }

    @Test fun photoEditorAppliesEditsAndSavesCopy() {
        ui.tapDesc(TestMedia.PHOTOS[0])
        ui.tapDesc("Edit")
        ui.text("Save", 20_000)
        Shots.take("20_photo_editor_adjust")

        ui.tapText("Filters")
        ui.tapText("Noir")
        Thread.sleep(1500)
        Shots.take("21_photo_editor_filter_noir")

        ui.tapText("Crop")
        ui.tapText("1:1")
        Thread.sleep(1200)
        Shots.take("22_photo_editor_crop_square")

        ui.tapText("Frame")
        ui.tapText("Instant")
        Thread.sleep(1200)
        Shots.take("23_photo_editor_frame")

        ui.tapText("Save")
        ui.tapText("Save as copy")
        val saved = TestMedia.waitFor("pgtest_photo_1_edited.jpg", 60_000)
        assertNotNull(saved, "edited copy saved to the gallery")
        // The original must be untouched and the copy must be a valid, square-ish framed image.
        assertNotNull(TestMedia.find(TestMedia.PHOTOS[0]), "original still present")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        TestMedia.context.contentResolver.openInputStream(saved)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        assertTrue(opts.outWidth > 1000 && opts.outHeight > 1000, "full resolution export: ${opts.outWidth}x${opts.outHeight}")
        assertTrue(opts.outHeight > opts.outWidth, "instant frame adds a deeper bottom border")
        Thread.sleep(1000)
        Shots.take("24_after_photo_save")
    }

    @Test fun videoEditorExportsEditedVideo() {
        ui.tapDesc(TestMedia.VIDEO)
        ui.tapDesc("Edit")
        ui.text("Export", 20_000)
        Thread.sleep(3000) // thumbnails + preview
        Shots.take("30_video_editor")

        // Project tools: fade in from black, then add a timed title.
        ui.tapText("Fade in from black")
        ui.tapText("Text")
        ui.obj(By.clazz("android.widget.EditText")).text = "ProGallery test"
        ui.tapText("Done")
        Thread.sleep(1500)
        Shots.take("31_video_editor_title")

        val exportButtons = { ui.device.findObjects(By.text("Export")).sortedBy { it.visibleBounds.top } }
        exportButtons().first().click()
        ui.text("Export video")
        Shots.take("32_video_export_dialog")
        exportButtons().last().click()

        val out = TestMedia.waitFor("pgtest_clip_edited.mp4", 240_000)
        assertNotNull(out, "exported video saved to the gallery")
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(TestMedia.context, out)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue(dur in 3_500..4_600, "duration $dur ms")
            assertTrue(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes")
        } finally { mmr.release() }
        assertNotNull(TestMedia.find(TestMedia.VIDEO), "original video untouched")
        Thread.sleep(1000)
        Shots.take("33_after_video_export")
    }
}
