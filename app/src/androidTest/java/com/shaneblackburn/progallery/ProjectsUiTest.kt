package com.shaneblackburn.progallery

import android.graphics.Point
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Video projects through the UI: create from the picker, reorder, grade panel, resume, delete. */
@RunWith(AndroidJUnit4::class)
class ProjectsUiTest {

    @get:Rule val permissions = mediaPermissions()
    private val ui = Ui()

    @Before fun setUp() {
        TestMedia.seed()
        ui.launch()
    }

    private fun clipLeft(name: String) = ui.desc("Clip $name").visibleBounds.left

    /** Long-press (held still ~0.7 s) then drag, as a finger would. */
    private fun longPressDrag(from: Point, to: Point) {
        val hold = Array(8) { from }
        val path = Array(6) { i -> Point(from.x + (to.x - from.x) * (i + 1) / 6, from.y + (to.y - from.y) * (i + 1) / 6) }
        ui.device.swipe(hold + path + arrayOf(to, to), 20)
    }

    @Test fun createReorderResumeAndDeleteProject() {
        val photo = TestMedia.PHOTOS[0]
        val video = TestMedia.VIDEO

        ui.tapText("Library")
        assertTrue(ui.scrollToVertical(By.text("Video projects")), "Video projects tile in Library")
        ui.tapText("Video projects")
        ui.text("Video projects", 15_000) // Projects screen top bar
        Thread.sleep(800)
        Shots.take("69_projects_empty")
        ui.tapDesc("New video") // FAB (content description set for reliable matching)
        ui.tapDesc("Pick $photo")
        ui.tapDesc("Pick $video")
        Thread.sleep(500)
        Shots.take("70_new_video_picker")
        ui.tapText("Create (2)")

        // Editor opens with both items, in the order they were picked.
        ui.text("Export", 30_000)
        ui.desc("Clip $video", 20_000)
        Thread.sleep(3000)
        Shots.take("71_project_editor")
        assertTrue(clipLeft(photo) < clipLeft(video), "photo first (picked first)")
        val projectName = ui.obj(By.textStartsWith("Video ")).text

        // Long-press and drag the photo past the video.
        val p = ui.desc("Clip $photo").visibleBounds
        val v = ui.desc("Clip $video").visibleBounds
        longPressDrag(Point(p.centerX(), p.centerY()), Point(v.right - 10, v.centerY()))
        Thread.sleep(1500)
        Shots.take("72_after_reorder")
        assertTrue(clipLeft(video) < clipLeft(photo), "video moved before the photo")

        // The colour grade panel offers the new controls (one per group confirms it; the
        // deeper Light/Colour sliders scroll off a small panel, and "Colour" as a group
        // chip would collide with the Colour tab, so check the unambiguous Light + Detail).
        ui.tapDesc("Clip $video")
        ui.tapText("Colour")
        assertTrue(ui.has(By.text("Exposure"), 8_000), "Light group: Exposure slider")
        ui.tapText("Detail")
        assertTrue(ui.has(By.text("Sharpness"), 8_000), "Detail group: Sharpness slider")
        Shots.take("73_grade_panel")
        ui.back() // deselect

        // Close: kept projects save automatically, so no prompt.
        Thread.sleep(1500) // autosave debounce
        ui.tapDesc("Close")
        assertTrue(!ui.has(By.text("Keep this video project?"), 1_000), "no prompt for a saved project")
        ui.text(projectName, 10_000)
        assertTrue(ui.has(By.text("2 items · 0:07")), "project row shows items and length")
        Shots.take("74_projects_list")

        // Resume: the reordered timeline was saved.
        ui.tapText(projectName)
        ui.desc("Clip $video", 20_000)
        Thread.sleep(1500)
        assertTrue(clipLeft(video) < clipLeft(photo), "order kept after reopening")
        ui.tapDesc("Close")

        // Delete the project (media untouched).
        ui.tapDesc("More options for $projectName")
        ui.tapText("Delete")
        ui.device.wait(Until.findObjects(By.text("Delete")), 5_000)?.maxByOrNull { it.visibleBounds.top }?.click()
        assertTrue(ui.device.wait(Until.gone(By.text(projectName)), 5_000), "project removed from the list")
        assertTrue(TestMedia.find(photo) != null && TestMedia.find(video) != null, "media untouched")
    }
}
