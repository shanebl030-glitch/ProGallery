package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.TestData.item
import com.shaneblackburn.progallery.core.model.AlbumBuilder
import com.shaneblackburn.progallery.core.model.AlbumSort
import com.shaneblackburn.progallery.core.model.MediaCategory
import com.shaneblackburn.progallery.core.model.MediaType
import com.shaneblackburn.progallery.core.model.NaturalOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelTest {

    @Test fun extensionAndFlags() {
        assertEquals("jpg", item(1, name = "a.JPG").extension)
        assertEquals("", item(1, name = "noext").extension)
        assertEquals("", item(1, name = ".hidden").extension)
        assertEquals("", item(1, name = "trailing.").extension)
        assertTrue(item(1, name = "x.gif", mime = "image/gif").isGif)
        assertTrue(item(1, name = "x.DNG", mime = "image/x-adobe-dng").isRaw)
        assertTrue(item(1, name = "x.cr3", mime = "application/octet-stream").isRaw)
        assertFalse(item(1, name = "x.jpg").isRaw)
        assertTrue(item(1, name = "MVIMG_20240101_1.jpg").isMotionPhotoCandidate)
        assertTrue(item(1, name = "PXL_20240101.MP.jpg").isMotionPhotoCandidate)
        assertFalse(item(1, name = "PXL_20240101.jpg").isMotionPhotoCandidate)
    }

    @Test fun effectiveDateFallsBack() {
        assertEquals(5, item(1, taken = 5, modified = 9, added = 7).effectiveDate)
        assertEquals(9, item(1, taken = 0, modified = 9, added = 7).effectiveDate)
        assertEquals(7, item(1, taken = 0, modified = 0, added = 7).effectiveDate)
    }

    @Test fun orientationSwapsDisplayDims() {
        val rotated = item(1, w = 4000, h = 3000, orientation = 90)
        assertEquals(3000, rotated.displayWidth)
        assertEquals(4000, rotated.displayHeight)
        assertEquals(4000, item(1, w = 4000, h = 3000, orientation = 180).displayWidth)
    }

    @Test fun categories() {
        assertEquals(MediaCategory.CAMERA, item(1, path = "DCIM/Camera/").category)
        assertEquals(MediaCategory.SCREENSHOT, item(1, path = "Pictures/Screenshots/", bucket = "Screenshots").category)
        assertEquals(MediaCategory.SCREEN_RECORDING, item(1, path = "Movies/ScreenRecordings/", bucket = "ScreenRecordings").category)
        assertEquals(MediaCategory.DOWNLOAD, item(1, path = "Download/", bucket = "Download").category)
        assertEquals(MediaCategory.MESSAGING, item(1, path = "Android/media/com.whatsapp/WhatsApp/Media/", bucket = "WhatsApp Images").category)
        assertEquals(MediaCategory.OTHER, item(1, path = "Pictures/Edits/", bucket = "Edits").category)
    }

    @Test fun naturalOrder() {
        val sorted = listOf("IMG_10.jpg", "img_2.jpg", "IMG_1.jpg", "IMG_02.jpg", "a", "B").sortedWith(NaturalOrder)
        assertEquals(listOf("a", "B", "IMG_1.jpg", "img_2.jpg", "IMG_02.jpg", "IMG_10.jpg"), sorted)
        // Very long digit runs must not overflow.
        assertTrue(NaturalOrder.compare("x99999999999999999999999", "x100000000000000000000000") < 0)
        assertEquals(0, NaturalOrder.compare("same", "same"))
        // Antisymmetry on a mixed sample
        val sample = listOf("a1", "A1", "a01", "a10", "a2b", "a2a", "", "1", "01")
        for (x in sample) for (y in sample) {
            assertEquals(Integer.signum(NaturalOrder.compare(x, y)), -Integer.signum(NaturalOrder.compare(y, x)), "$x vs $y")
        }
    }

    @Test fun albumsBuildAndSort() {
        val items = listOf(
            item(1, bucketId = 10, bucket = "Holiday", path = "Pictures/Holiday/", taken = 100, size = 5),
            item(2, bucketId = 10, bucket = "Holiday", path = "Pictures/Holiday/", taken = 300, size = 5, type = MediaType.VIDEO),
            item(3, bucketId = 20, bucket = "Camera", path = "DCIM/Camera/", taken = 50, size = 1),
            item(4, bucketId = 30, bucket = "Alpha", path = "Pictures/Alpha/", taken = 200, size = 100),
            item(5, bucketId = 30, bucket = "Alpha", path = "Pictures/Alpha/", taken = 900, trashed = true),
        )
        val albums = AlbumBuilder.build(items, hiddenBuckets = setOf(30))
        // Camera is always first, then by recency (trashed item ignored for Alpha's date).
        assertEquals(listOf(20L, 10L, 30L), albums.map { it.bucketId })
        val holiday = albums.first { it.bucketId == 10L }
        assertEquals(2, holiday.count)
        assertEquals(1, holiday.videoCount)
        assertEquals(2L, holiday.cover?.id)
        assertEquals(1, albums.first { it.bucketId == 30L }.count)
        assertTrue(albums.first { it.bucketId == 30L }.isHidden)

        val byName = AlbumBuilder.build(items, sort = AlbumSort.NAME)
        assertEquals(listOf(20L, 30L, 10L), byName.map { it.bucketId })
        val pinned = AlbumBuilder.build(items, pinnedBuckets = setOf(10), sort = AlbumSort.NAME)
        assertEquals(10L, pinned.first().bucketId)
    }
}
