package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.TestData.ZONE
import com.shaneblackburn.progallery.core.TestData.at
import com.shaneblackburn.progallery.core.TestData.item
import com.shaneblackburn.progallery.core.query.DateGrouper
import com.shaneblackburn.progallery.core.query.FilterSpec
import com.shaneblackburn.progallery.core.query.GalleryDate
import com.shaneblackburn.progallery.core.query.GridEntry
import com.shaneblackburn.progallery.core.query.GroupBy
import com.shaneblackburn.progallery.core.query.SortField
import com.shaneblackburn.progallery.core.query.SortSpec
import com.shaneblackburn.progallery.core.query.applyFilterAndSort
import com.shaneblackburn.progallery.core.model.MediaItem
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fix 2 regression — "recently added" media (an edited/exported video that keeps its original
 * capture date, or anything added today) surfaces under **Today** without changing any stored date,
 * then settles back to its capture-date position the next day, with no duplicate entries.
 */
class GalleryDateTest {

    private val today = LocalDate.of(2026, 10, 31)

    // Exported today at 16:00, but the original footage was shot in 2023 (capture date kept).
    private val exportedToday = item(100, name = "edited.mp4", taken = at(2023, 5, 1), added = at(2026, 10, 31, 16))
    // A photo actually taken today at 09:00.
    private val photoToday = item(101, name = "today.jpg", taken = at(2026, 10, 31, 9))
    // Older library media, captured and added the same earlier day.
    private val older = item(102, name = "old.jpg", taken = at(2026, 10, 29, 12))

    // ---- GalleryDate.of ----

    @Test fun boostsMediaAddedTodayWithOlderCaptureDate() {
        assertEquals(exportedToday.dateAdded, GalleryDate.of(exportedToday, today, ZONE),
            "an item added today with an older capture date groups by its add time (Today)")
    }

    @Test fun doesNotBoostMediaCapturedToday() {
        assertEquals(photoToday.effectiveDate, GalleryDate.of(photoToday, today, ZONE),
            "a photo taken today keeps its own capture date (already Today)")
    }

    @Test fun doesNotBoostMediaAddedBeforeToday() {
        // Same clip, viewed the NEXT day: no longer added "today", so it settles to its capture date.
        val tomorrow = LocalDate.of(2026, 11, 1)
        assertEquals(exportedToday.effectiveDate, GalleryDate.of(exportedToday, tomorrow, ZONE),
            "the day after it was added, the item returns to its original capture-date position")
    }

    @Test fun fallsBackWhenNoAddDate() {
        val noAdd = item(103, taken = at(2023, 5, 1), added = 0)
        assertEquals(noAdd.effectiveDate, GalleryDate.of(noAdd, today, ZONE))
    }

    @Test fun doesNotBoostFutureCaptureDate() {
        // Bad metadata with a future capture date added today: leave it on its own timestamp, don't pull back.
        val future = item(104, taken = at(2027, 1, 1), added = at(2026, 10, 31, 10))
        assertEquals(future.effectiveDate, GalleryDate.of(future, today, ZONE))
    }

    @Test fun usesLocalDateInTheGivenZone() {
        // Added at 00:30 local (UTC+13) on the 31st — which is 11:30 on the 30th in UTC.
        val justAfterMidnight = item(105, taken = at(2023, 5, 1), added = at(2026, 10, 31, 0, 30))
        assertEquals(justAfterMidnight.dateAdded, GalleryDate.of(justAfterMidnight, today, ZONE),
            "boosted: it was added today in the local zone")
        assertEquals(justAfterMidnight.effectiveDate, GalleryDate.of(justAfterMidnight, today, ZoneOffset.UTC),
            "in UTC that instant is the 30th, so no boost — local-date handling is zone-correct")
    }

    // ---- Only the capture-date ordering is boosted ----

    @Test fun dateAddedAndDateModifiedSortsAreUnaffected() {
        assertEquals(exportedToday.dateAdded, SortSpec(SortField.DATE_ADDED).dateOf(exportedToday, today, ZONE))
        assertEquals(exportedToday.dateModified, SortSpec(SortField.DATE_MODIFIED).dateOf(exportedToday, today, ZONE))
        // And the boosted DATE_TAKEN path matches GalleryDate.of.
        assertEquals(GalleryDate.of(exportedToday, today, ZONE), SortSpec(SortField.DATE_TAKEN).dateOf(exportedToday, today, ZONE))
    }

    // ---- Integration: sort + grouping stay in lockstep, one entry per item ----

    private fun headersFor(entries: List<GridEntry>) = entries.filterIsInstance<GridEntry.Header>()
    private fun itemsFor(entries: List<GridEntry>) = entries.filterIsInstance<GridEntry.Item>()

    private fun groupTimeline(items: List<MediaItem>, on: LocalDate): List<GridEntry> {
        val sort = SortSpec(SortField.DATE_TAKEN, ascending = false)
        val sorted = items.applyFilterAndSort(FilterSpec(), sort, on, ZONE)
        return DateGrouper(ZONE, on).group(sorted, GroupBy.DAY) { sort.dateOf(it, on, ZONE) }
    }

    @Test fun exportedVideoAppearsUnderTodayAtTheTopThenSettlesNextDay() {
        val all = listOf(exportedToday, photoToday, older)

        val entries = groupTimeline(all, today)
        val todayHeader = headersFor(entries).firstOrNull { it.label == "Today" }
        assertTrue(todayHeader != null, "a Today section exists")
        assertTrue(exportedToday.id in todayHeader.itemIds, "the exported video is in Today")
        assertTrue(photoToday.id in todayHeader.itemIds, "today's photo is in Today")
        // Exported-today (16:00) sorts above the photo taken today (09:00) within Today.
        assertEquals(exportedToday.id, todayHeader.itemIds.first(), "freshly exported media sits at the top of Today")
        // The older item keeps its own day and is NOT pulled into Today.
        assertFalse(older.id in todayHeader.itemIds)
        assertTrue(headersFor(entries).any { it.label != "Today" && older.id in it.itemIds })
        // No duplicates: the exported video shows exactly once, in exactly one section.
        assertEquals(1, itemsFor(entries).count { it.item.id == exportedToday.id })
        assertEquals(1, headersFor(entries).count { exportedToday.id in it.itemIds })

        // The next day: it is no longer "added today", so it groups under its 2023 capture date.
        val tomorrow = LocalDate.of(2026, 11, 1)
        val next = groupTimeline(all, tomorrow)
        val tomorrowToday = headersFor(next).firstOrNull { it.label == "Today" }
        assertFalse(tomorrowToday != null && exportedToday.id in tomorrowToday.itemIds,
            "the day after, the exported video is no longer in Today")
        val capDay = DateGrouper(ZONE, tomorrow).sectionKey(exportedToday.effectiveDate, GroupBy.DAY)
        assertTrue(headersFor(next).any { it.sectionKey == capDay && exportedToday.id in it.itemIds },
            "it settles under its original (2023) capture date")
    }
}
