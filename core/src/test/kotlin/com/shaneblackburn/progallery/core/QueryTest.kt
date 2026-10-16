package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.TestData.ZONE
import com.shaneblackburn.progallery.core.TestData.at
import com.shaneblackburn.progallery.core.TestData.item
import com.shaneblackburn.progallery.core.model.MediaType
import com.shaneblackburn.progallery.core.query.DateGrouper
import com.shaneblackburn.progallery.core.query.FilterSpec
import com.shaneblackburn.progallery.core.query.GridEntry
import com.shaneblackburn.progallery.core.query.GroupBy
import com.shaneblackburn.progallery.core.query.SearchQuery
import com.shaneblackburn.progallery.core.query.SmartCollection
import com.shaneblackburn.progallery.core.query.SmartCollections
import com.shaneblackburn.progallery.core.query.SortField
import com.shaneblackburn.progallery.core.query.SortSpec
import com.shaneblackburn.progallery.core.query.applyFilterAndSort
import java.time.LocalDate
import java.time.Month
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryTest {

    private val items = listOf(
        item(1, name = "IMG_10.jpg", taken = at(2026, 10, 17, 9), size = 3_000_000),
        item(2, name = "IMG_2.jpg", taken = at(2026, 10, 17, 8), size = 1_000_000, fav = true),
        item(3, name = "clip.mp4", type = MediaType.VIDEO, taken = at(2026, 10, 16), size = 50_000_000, duration = 9_000,
            bucketId = 2, bucket = "Movies", path = "Movies/"),
        item(4, name = "shot.png", mime = "image/png", taken = at(2025, 6, 3), bucketId = 3, bucket = "Screenshots",
            path = "Pictures/Screenshots/", size = 200_000),
        item(5, name = "gone.jpg", taken = at(2026, 10, 17, 10), trashed = true),
    )

    @Test fun sortIsTotalAndDirectional() {
        val byDate = items.applyFilterAndSort(FilterSpec(), SortSpec(SortField.DATE_TAKEN, ascending = false))
        assertEquals(listOf(1L, 2L, 3L, 4L), byDate.map { it.id }) // trashed excluded
        val byName = items.applyFilterAndSort(FilterSpec(), SortSpec(SortField.NAME, ascending = true))
        assertEquals(listOf("clip.mp4", "IMG_2.jpg", "IMG_10.jpg", "shot.png"), byName.map { it.name })
        val bySize = items.applyFilterAndSort(FilterSpec(), SortSpec(SortField.SIZE, ascending = false))
        assertEquals(3L, bySize.first().id)
        // Equal keys -> stable by id
        val ties = listOf(item(7, taken = 1), item(6, taken = 1), item(8, taken = 1))
        assertEquals(listOf(8L, 7L, 6L), ties.sortedWith(SortSpec().comparator()).map { it.id })
        assertEquals(listOf(6L, 7L, 8L), ties.sortedWith(SortSpec(ascending = true).comparator()).map { it.id })
    }

    @Test fun filterRespectsTypesFavouritesAndHidden() {
        assertEquals(listOf(3L), items.filter(FilterSpec(types = setOf(MediaType.VIDEO))::matches).map { it.id })
        assertEquals(listOf(2L), items.filter(FilterSpec(favoritesOnly = true)::matches).map { it.id })
        val hidden = FilterSpec(hiddenBuckets = setOf(3L))
        assertFalse(items.filter(hidden::matches).any { it.id == 4L })
        assertTrue(items.filter(hidden.copy(includeHiddenBuckets = true)::matches).any { it.id == 4L })
        assertTrue(FilterSpec().isDefault)
        assertFalse(FilterSpec(favoritesOnly = true).isDefault)
    }

    @Test fun dateGroupingLabels() {
        val g = DateGrouper(ZONE, LocalDate.of(2026, 10, 17), Locale.ENGLISH)
        assertEquals("Today", g.dayLabel(LocalDate.of(2026, 10, 17)))
        assertEquals("Yesterday", g.dayLabel(LocalDate.of(2026, 10, 16)))
        assertEquals("Monday", g.dayLabel(LocalDate.of(2026, 10, 12)))
        assertEquals("3 October", g.dayLabel(LocalDate.of(2026, 10, 3)))
        assertEquals("3 June 2025", g.dayLabel(LocalDate.of(2025, 6, 3)))
        assertEquals("June 2025", g.label(at(2025, 6, 3), GroupBy.MONTH))
        assertEquals("October", g.label(at(2026, 10, 3), GroupBy.MONTH))
    }

    @Test fun dateGroupingStructure() {
        val g = DateGrouper(ZONE, LocalDate.of(2026, 10, 17), Locale.ENGLISH)
        val sorted = items.applyFilterAndSort(FilterSpec(), SortSpec())
        val entries = g.group(sorted, GroupBy.DAY)
        val headers = entries.filterIsInstance<GridEntry.Header>()
        assertEquals(listOf("Today", "Yesterday", "3 June 2025"), headers.map { it.label })
        assertEquals(listOf(1L, 2L), headers[0].itemIds)
        assertEquals(sorted.size + headers.size, entries.size)
        // Keys must be unique for LazyGrid
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        // Item indexes map back to the flat list for the viewer
        entries.filterIsInstance<GridEntry.Item>().forEach { assertEquals(sorted[it.index].id, it.item.id) }
        assertEquals(sorted.size, g.group(sorted, GroupBy.NONE).size)
        assertTrue(g.group(emptyList(), GroupBy.DAY).isEmpty())
    }

    @Test fun midnightBoundaryUsesLocalZone() {
        val g = DateGrouper(ZONE, LocalDate.of(2026, 10, 17), Locale.ENGLISH)
        val lateNight = at(2026, 10, 16, 23, 59)
        val earlyMorning = at(2026, 10, 17, 0, 1)
        assertEquals("2026-10-16", g.sectionKey(lateNight, GroupBy.DAY))
        assertEquals("2026-10-17", g.sectionKey(earlyMorning, GroupBy.DAY))
    }

    @Test fun searchParsing() {
        val q = SearchQuery.parse("type:video album:mov >10mb june 2026 holiday", Locale.ENGLISH)
        assertEquals(setOf(MediaType.VIDEO), q.types)
        assertEquals(listOf("mov"), q.albumContains)
        assertEquals(10_000_000L, q.minBytes)
        assertEquals(setOf(Month.JUNE), q.months)
        assertEquals(setOf(2026), q.years)
        assertEquals(listOf("holiday"), q.terms)

        val q2 = SearchQuery.parse("\"img 2\" fav ext:.PNG after:2025-01-01 before:2026-01-01 screenshots")
        assertEquals(listOf("img 2"), q2.terms)
        assertTrue(q2.favoritesOnly)
        assertEquals(setOf("png"), q2.extensions)
        assertEquals(LocalDate.of(2025, 1, 1), q2.after)
        assertTrue(q2.categories.isNotEmpty())

        assertTrue(SearchQuery.parse("   ").isEmpty)
        // Unknown operator falls back to a plain term
        assertEquals(listOf("foo:bar"), SearchQuery.parse("foo:bar").terms)
        // Bad date is treated as a term, not a crash
        assertEquals(listOf("after:notadate"), SearchQuery.parse("after:notadate").terms)
    }

    @Test fun searchMatching() {
        fun ids(s: String) = items.filter { SearchQuery.parse(s, Locale.ENGLISH).matches(it, ZONE) }.map { it.id }
        assertEquals(listOf(1L, 2L), ids("img"))
        assertEquals(listOf(3L), ids("videos"))
        assertEquals(listOf(4L), ids("screenshot"))
        assertEquals(listOf(4L), ids("june"))
        assertEquals(listOf(4L), ids("2025"))
        assertEquals(listOf(2L), ids("fav"))
        assertEquals(listOf(3L), ids(">10mb"))
        assertEquals(listOf(4L), ids("ext:png"))
        assertEquals(listOf(3L), ids("movies clip")) // album name OR file name per term
        assertEquals(emptyList(), ids("gone")) // trashed never matches
        assertEquals(listOf(1L, 2L, 3L), ids("after:2025-12-31"))
    }

    @Test fun smartCollections() {
        val now = at(2026, 10, 17, 12)
        val recent = SmartCollections.select(SmartCollection.RECENTLY_ADDED, items, now)
        assertEquals(listOf(1L, 2L, 3L), recent.map { it.id })
        assertEquals(listOf(3L), SmartCollections.select(SmartCollection.VIDEOS, items, now).map { it.id })
        assertEquals(listOf(4L), SmartCollections.select(SmartCollection.SCREENSHOTS, items, now).map { it.id })
        val viewed = SmartCollections.select(SmartCollection.RECENTLY_VIEWED, items, now, recentIds = listOf(4, 99, 5, 1))
        assertEquals(listOf(4L, 1L), viewed.map { it.id }) // missing + trashed skipped, order kept
        assertTrue(SmartCollections.hasIntrinsicOrder(SmartCollection.RECENTLY_VIEWED))
    }
}
