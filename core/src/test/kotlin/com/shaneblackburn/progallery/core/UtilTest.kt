package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.format.Formatters
import com.shaneblackburn.progallery.core.util.FileNames
import com.shaneblackburn.progallery.core.util.RecentList
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UtilTest {

    @Test fun fileSizes() {
        val l = Locale.ENGLISH
        assertEquals("0 B", Formatters.fileSize(0, l))
        assertEquals("999 B", Formatters.fileSize(999, l))
        assertEquals("1.00 kB", Formatters.fileSize(1000, l))
        assertEquals("1.50 MB", Formatters.fileSize(1_500_000, l))
        assertEquals("12.3 MB", Formatters.fileSize(12_300_000, l))
        assertEquals("123 MB", Formatters.fileSize(123_000_000, l))
        assertEquals("1.00 GB", Formatters.fileSize(999_999_999, l)) // rounds up across unit boundary
        assertEquals("—", Formatters.fileSize(-1, l))
    }

    @Test fun durations() {
        assertEquals("0:00", Formatters.duration(0))
        assertEquals("0:07", Formatters.duration(7_999))
        assertEquals("4:05", Formatters.duration(245_000))
        assertEquals("1:02:03", Formatters.duration(3_723_000))
        assertEquals("0:00", Formatters.duration(-5))
        assertEquals("1:02.35", Formatters.timecode(62_350))
    }

    @Test fun exifFormatting() {
        assertEquals("1/250 s", Formatters.exposureTime(0.004))
        assertEquals("2 s", Formatters.exposureTime(2.0))
        assertEquals("ƒ/1.8", Formatters.aperture(1.8))
        assertEquals("26 mm", Formatters.focalLength(26.0))
        assertEquals("4.3 mm", Formatters.focalLength(4.3))
        assertEquals("4000 × 3000 (12.0 MP)", Formatters.resolution(4000, 3000, Locale.ENGLISH))
        assertEquals("Unknown", Formatters.resolution(0, 3000))
        assertEquals("1 item", Formatters.count(1, "item"))
        assertEquals("3 items", Formatters.count(3, "item"))
    }

    @Test fun nameValidation() {
        assertIs<FileNames.Validation.Ok>(FileNames.validate("Holiday 2026"))
        assertIs<FileNames.Validation.Invalid>(FileNames.validate("  "))
        assertIs<FileNames.Validation.Invalid>(FileNames.validate("a/b"))
        assertIs<FileNames.Validation.Invalid>(FileNames.validate(".secret"))
        assertIs<FileNames.Validation.Invalid>(FileNames.validate(".."))
        assertIs<FileNames.Validation.Invalid>(FileNames.validate("what?"))
        assertIs<FileNames.Validation.Invalid>(FileNames.validate("x".repeat(256)))
        assertIs<FileNames.Validation.Invalid>(FileNames.validate("end."))
        // Multi-byte names are measured in bytes
        assertIs<FileNames.Validation.Invalid>(FileNames.validate("é".repeat(128)))
    }

    @Test fun uniqueAndEditedNames() {
        assertEquals("a.jpg", FileNames.uniqueName("a.jpg", setOf("b.jpg")))
        assertEquals("a (2).jpg", FileNames.uniqueName("a.jpg", setOf("A.JPG")))
        assertEquals("a (3).jpg", FileNames.uniqueName("a.jpg", setOf("a.jpg", "a (2).jpg")))
        // Re-suffixing strips the old counter instead of producing "a (2) (2).jpg"
        assertEquals("a (3).jpg", FileNames.uniqueName("a (2).jpg", setOf("a (2).jpg", "a.jpg")))
        assertEquals("IMG_1_edited.jpg", FileNames.editedCopyName("IMG_1.jpg", emptySet()))
        assertEquals("IMG_1_edited (2).jpg", FileNames.editedCopyName("IMG_1.jpg", setOf("IMG_1_edited.jpg")))
        assertEquals("IMG_1_edited.png", FileNames.editedCopyName("IMG_1_edited.jpg", emptySet(), "png"))
        assertEquals("photo.jpg", FileNames.withExtension("photo", "old.jpg"))
        assertEquals("photo.JPG", FileNames.withExtension("photo.JPG", "old.jpg"))
        assertEquals("README", FileNames.withExtension("README", "LICENSE"))
        assertEquals("Pictures/Holiday/", FileNames.normaliseRelativePath("/Pictures//Holiday"))
        assertEquals("", FileNames.normaliseRelativePath("///"))
    }

    @Test fun recentList() {
        var r = RecentList(capacity = 3)
        r = r.touch(1).touch(2).touch(3).touch(2)
        assertEquals(listOf(2L, 3L, 1L), r.ids)
        r = r.touch(4)
        assertEquals(listOf(4L, 2L, 3L), r.ids)
        assertEquals(listOf(4L, 3L), r.remove(setOf(2)).ids)
        assertEquals(listOf(2L), r.retainExisting(setOf(2, 9)).ids)
        val parsed = RecentList.parse(r.serialise() + ",junk,,4", capacity = 3)
        assertEquals(listOf(4L, 2L, 3L), parsed.ids)
        assertTrue(RecentList.parse(null).ids.isEmpty())
    }
}
