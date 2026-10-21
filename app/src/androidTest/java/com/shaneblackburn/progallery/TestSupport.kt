package com.shaneblackburn.progallery

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File

/** Seeds and cleans up test media in dedicated "PGTest" folders. */
object TestMedia {
    const val PHOTO_DIR = "Pictures/PGTest/"
    const val VIDEO_DIR = "Movies/PGTest/"
    val PHOTOS = listOf("pgtest_photo_1.jpg", "pgtest_photo_2.jpg", "pgtest_photo_3.jpg")
    const val VIDEO = "pgtest_clip.mp4"

    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Deletes everything in the test folders (including edited copies and exports). */
    fun cleanup() {
        val r = context.contentResolver
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        // Include pending (half-written) and trashed items, which queries hide by default;
        // leftovers would otherwise collide with re-seeded files.
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(PHOTO_DIR, VIDEO_DIR))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        r.query(files, arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE), args, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val isVideo = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val uri = ContentUris.withAppendedId(
                    if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
                runCatching { r.delete(uri, null, null) }.onFailure { Log.w("TestMedia", "delete failed $uri", it) }
            }
        }
    }

    fun seed() {
        cleanup()
        PHOTOS.forEachIndexed { i, name -> insertPhoto(name, i) }
        insertVideo()
    }

    private fun insertPhoto(name: String, index: Int) {
        val r = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, PHOTO_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        val bmp = samplePhoto(1600, 1200, index)
        r.openOutputStream(uri, "w")!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        r.openFileDescriptor(uri, "rw")!!.use { pfd ->
            val exif = ExifInterface(pfd.fileDescriptor)
            exif.setAttribute(ExifInterface.TAG_MAKE, "ProGalleryTest")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Emulator Cam")
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:01 12:00:0$index")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, "1.8")
            exif.saveAttributes()
        }
        r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun insertVideo() {
        val r = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, VIDEO)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, VIDEO_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        r.openOutputStream(uri, "w")!!.use { out -> testCtx.assets.open(VIDEO).use { it.copyTo(out) } }
        r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    /** Colourful, detailed test image (gradient, shapes, text) so edits are visible. */
    fun samplePhoto(w: Int, h: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val palettes = listOf(intArrayOf(0xFF1E88E5.toInt(), 0xFFFFB74D.toInt()), intArrayOf(0xFF43A047.toInt(), 0xFFE1F5FE.toInt()),
            intArrayOf(0xFF8E24AA.toInt(), 0xFFFF7043.toInt()))
        val pal = palettes[seed % palettes.size]
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), pal[0], pal[1], Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(255, 255, 235, 150); c.drawCircle(w * 0.75f, h * 0.3f, h * 0.12f, p)
        p.color = Color.argb(255, 40, 60, 90)
        c.drawPath(android.graphics.Path().apply {
            moveTo(0f, h.toFloat()); lineTo(w * 0.3f, h * 0.45f); lineTo(w * 0.55f, h * 0.8f)
            lineTo(w * 0.75f, h * 0.55f); lineTo(w.toFloat(), h * 0.9f); lineTo(w.toFloat(), h.toFloat()); close()
        }, p)
        p.color = Color.WHITE; p.textSize = h * 0.08f
        c.drawText("PGTest ${seed + 1}", w * 0.05f, h * 0.15f, p)
        return bmp
    }

    /** MediaStore uri of an item in the test folders by display name, or null. */
    fun find(name: String): Uri? {
        val r = context.contentResolver
        r.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)",
            arrayOf(name, PHOTO_DIR, VIDEO_DIR), null)?.use { c ->
            if (c.moveToFirst()) {
                val isVideo = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                return ContentUris.withAppendedId(
                    if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), c.getLong(0))
            }
        }
        return null
    }

    fun waitFor(name: String, timeoutMs: Long): Uri? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            find(name)?.let { return it }
            Thread.sleep(500)
        }
        return null
    }
}

/** Saves screenshots where CI collects them. */
object Shots {
    private val dirs: List<File> by lazy {
        // App-private external folder: not indexed by MediaStore, so screenshots never
        // show up inside the gallery under test.
        listOfNotNull(TestMedia.context.getExternalFilesDir("screenshots"))
    }

    fun take(name: String): Bitmap? {
        Thread.sleep(600) // let animations settle
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return null
        save(name, bmp)
        return bmp
    }

    /** Mean R, G, B of a horizontal band. */
    fun brightnessRgb(bmp: Bitmap, top: Float, bottom: Float): DoubleArray {
        val sum = DoubleArray(3); var n = 0
        for (y in (bmp.height * top).toInt() until (bmp.height * bottom).toInt() step 4) {
            for (x in 0 until bmp.width step 4) {
                val p = bmp.getPixel(x, y)
                sum[0] += (p shr 16) and 255; sum[1] += (p shr 8) and 255; sum[2] += p and 255; n++
            }
        }
        return DoubleArray(3) { if (n == 0) 0.0 else Math.round(sum[it] / n * 10) / 10.0 }
    }

    /**
     * Mean colour spread (max - min channel) of the photo pixels in a band, skipping the
     * editor's near-black background. ~0 for a monochrome image.
     */
    fun colourSpread(bmp: Bitmap, top: Float, bottom: Float): Double {
        var sum = 0L; var n = 0
        for (y in (bmp.height * top).toInt() until (bmp.height * bottom).toInt() step 4) {
            for (x in 0 until bmp.width step 4) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                if (r + g + b < 120) continue // editor background / letterbox
                sum += maxOf(r, g, b) - minOf(r, g, b); n++
            }
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /** Mean brightness (0..255) of a horizontal band of a screenshot, as fractions of height. */
    fun brightness(bmp: Bitmap, top: Float, bottom: Float): Double {
        var sum = 0L; var n = 0
        for (y in (bmp.height * top).toInt() until (bmp.height * bottom).toInt() step 4) {
            for (x in 0 until bmp.width step 4) {
                val p = bmp.getPixel(x, y)
                sum += ((p shr 16) and 255) + ((p shr 8) and 255) + (p and 255); n++
            }
        }
        return if (n == 0) 0.0 else sum.toDouble() / n / 3
    }

    fun save(name: String, bmp: Bitmap) {
        for (d in dirs) {
            runCatching {
                d.mkdirs()
                File(d, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.onFailure { Log.w("Shots", "couldn't save $name to $d", it) }
        }
    }
}

/** Writes the app's thread stacks to the screenshots folder (diagnostics). */
fun dumpThreads(name: String) {
    val text = Thread.getAllStackTraces().entries.joinToString("\n\n") { (t, st) ->
        "THREAD ${t.name} (${t.state})\n  " + st.take(30).joinToString("\n  ")
    }
    TestMedia.context.getExternalFilesDir("screenshots")?.let { d ->
        d.mkdirs(); File(d, "$name.txt").writeText(text)
    }
    Log.i("ThreadDump", "$name\n" + text.take(20_000))
}

/** UI Automator helpers: wait for and act on on-screen text / content descriptions. */
class Ui {
    val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun launch() {
        // The screen may have turned off during earlier (non-UI) tests.
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.pressHome()
        val ctx = TestMedia.context
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        if (!device.wait(Until.hasObject(By.pkg(ctx.packageName)), 20_000)) {
            // The CI emulator is occasionally overloaded (System UI ANR dialogs); retry once.
            device.findObject(By.text("Wait"))?.click()
            device.pressHome()
            ctx.startActivity(intent)
            check(device.wait(Until.hasObject(By.pkg(ctx.packageName)), 30_000)) { "App didn't start" }
        }
    }

    fun obj(sel: BySelector, timeoutMs: Long = 15_000): UiObject2 =
        device.wait(Until.findObject(sel), timeoutMs) ?: error("Not found on screen: $sel")

    fun text(t: String, timeoutMs: Long = 15_000) = obj(By.text(t), timeoutMs)
    fun desc(d: String, timeoutMs: Long = 15_000) = obj(By.desc(d), timeoutMs)
    fun has(sel: BySelector, timeoutMs: Long = 5_000): Boolean = device.wait(Until.hasObject(sel), timeoutMs)

    fun tapText(t: String) = text(t).click()

    /** Taps [t], scrolling horizontal lists to the right to reveal it if needed (like a user would). */
    fun tapTextScrolling(t: String) {
        if (has(By.text(t), 1_500)) { text(t).click(); return }
        for (list in device.findObjects(By.scrollable(true))) {
            repeat(5) {
                runCatching { list.scroll(androidx.test.uiautomator.Direction.RIGHT, 0.8f) }
                if (has(By.text(t), 700)) { text(t).click(); return }
            }
        }
        error("Not found even after scrolling: $t")
    }
    fun tapDesc(d: String) = desc(d).click()

    fun back() { device.pressBack(); device.waitForIdle() }
}

