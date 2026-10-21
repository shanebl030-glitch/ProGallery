package com.shaneblackburn.progallery

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Grants the same permissions a user grants on first launch. */
fun mediaPermissions(): GrantPermissionRule = if (Build.VERSION.SDK_INT >= 33) {
    GrantPermissionRule.grant(
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION, Manifest.permission.POST_NOTIFICATIONS,
    )
} else {
    GrantPermissionRule.grant(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
}

/**
 * End-to-end checks of the v0.1.0 gallery features, driven through the real UI.
 * Screenshots are saved for review (see CI "screenshots" output).
 */
@RunWith(AndroidJUnit4::class)
class GalleryUiTest {

    @get:Rule val permissions = mediaPermissions()
    private val ui = Ui()

    @Before fun setUp() {
        TestMedia.seed()
        ui.launch()
    }

    @Test fun photosTimelineAlbumsAndLibrary() {
        // Photos tab: seeded items appear in the grid.
        ui.desc(TestMedia.PHOTOS[0], 20_000)
        assertTrue(ui.has(By.desc(TestMedia.VIDEO)), "video thumbnail shown")
        Shots.take("01_photos_timeline")

        ui.tapText("Albums")
        ui.text("PGTest")
        Shots.take("02_albums")
        // Two "PGTest" albums exist (photos and video); open the one with 3 photos.
        ui.tapText("3")
        ui.desc(TestMedia.PHOTOS[0])
        Shots.take("03_album_opened")
        ui.back()

        ui.tapText("Library")
        ui.text("Favourites")
        ui.text("Videos")
        Shots.take("04_library")
    }

    @Test fun authorCreditInSettingsAndAbout() {
        ui.tapText("Library")
        ui.tapDesc("Settings")
        ui.text("Settings")
        // Scroll the settings list to the bottom to reach About and the footer credit.
        var found = false
        for (i in 0 until 10) {
            if (ui.has(By.textContains(AppInfo.AUTHOR_CREDIT), 800)) { found = true; break }
            ui.device.swipe(ui.device.displayWidth / 2, ui.device.displayHeight * 3 / 4, ui.device.displayWidth / 2, ui.device.displayHeight / 4, 30)
        }
        Shots.take("05_settings_footer")
        assertTrue(found, "credit in Settings footer")
        ui.tapText("About ProGallery")
        // Exact, case-sensitive match on the About screen.
        val credit = ui.text("Created by Shane BlackbuRn")
        assertTrue(credit.text == "Created by Shane BlackbuRn")
        Shots.take("06_about_credit")
    }

    @Test fun searchViewerDetailsAndVideoPlayer() {
        ui.tapText("Search")
        val field = ui.obj(By.clazz("android.widget.EditText"))
        field.text = "pgtest"
        ui.text("4 results")
        Shots.take("07_search_results")

        // Open a photo in the full-screen viewer and show its details.
        ui.tapDesc(TestMedia.PHOTOS[0])
        ui.desc("Details")
        Shots.take("08_viewer_photo")
        ui.tapDesc("Details")
        // EXIF section from the seeded Make/Model; the sheet opens half-way, so drag it up if needed.
        ui.text("Details")             // sheet is open
        Thread.sleep(800)              // opening animation
        for (i in 0 until 4) {
            if (ui.has(By.text("ProGalleryTest Emulator Cam"), 1_500)) break
            // Scroll the sheet's own content (the lowest scrollable on screen).
            ui.device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.bottom }
                ?.let { runCatching { it.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f) } }
        }
        ui.text("Camera")
        ui.text("ProGalleryTest Emulator Cam")
        Shots.take("09_viewer_details")
        ui.back()                      // close sheet
        ui.device.wait(Until.gone(By.text("ProGalleryTest Emulator Cam")), 5_000) // sheet closing animation
        ui.back()                      // close viewer
        if (!ui.has(By.clazz("android.widget.EditText"), 3_000)) ui.back()

        // Open the video: player controls appear.
        val field2 = ui.obj(By.clazz("android.widget.EditText"))
        field2.text = "pgtest_clip"
        ui.text("1 result")
        ui.tapDesc(TestMedia.VIDEO)
        assertTrue(ui.has(By.desc("Pause"), 10_000) || ui.has(By.desc("Play"), 2_000), "video controls shown")
        Thread.sleep(1500)
        Shots.take("10_video_player")
        assertTrue(ui.has(By.desc("Mute")), "mute control")
    }
}
