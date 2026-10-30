package com.shaneblackburn.progallery.core.query

import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.model.MediaType
import com.shaneblackburn.progallery.core.model.NaturalOrder
import java.time.LocalDate
import java.time.ZoneId

enum class SortField { DATE_TAKEN, DATE_MODIFIED, DATE_ADDED, NAME, SIZE, TYPE, DURATION, RESOLUTION }

data class SortSpec(val field: SortField = SortField.DATE_TAKEN, val ascending: Boolean = false) {

    /** Total, deterministic ordering (ties broken by id) so grids never "jump". */
    fun comparator(): Comparator<MediaItem> {
        val base: Comparator<MediaItem> = when (field) {
            SortField.DATE_TAKEN -> compareBy { it.effectiveDate }
            SortField.DATE_MODIFIED -> compareBy { it.dateModified }
            SortField.DATE_ADDED -> compareBy { it.dateAdded }
            SortField.NAME -> Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortField.SIZE -> compareBy { it.sizeBytes }
            SortField.TYPE -> compareBy<MediaItem> { it.type.ordinal }.thenBy { it.mimeType }
            SortField.DURATION -> compareBy { it.durationMs }
            SortField.RESOLUTION -> compareBy { it.width.toLong() * it.height.toLong() }
        }
        val directed = if (ascending) base else base.reversed()
        return directed.thenComparator { a, b -> if (ascending) a.id.compareTo(b.id) else b.id.compareTo(a.id) }
    }

    /** Whether date-section headers make sense for this ordering. */
    val isDateBased: Boolean
        get() = this.field == SortField.DATE_TAKEN || this.field == SortField.DATE_MODIFIED ||
            this.field == SortField.DATE_ADDED

    fun dateOf(item: MediaItem): Long = when (field) {
        SortField.DATE_MODIFIED -> item.dateModified
        SortField.DATE_ADDED -> item.dateAdded
        else -> item.effectiveDate
    }

    /**
     * Comparator that applies the "recently added" boost ([GalleryDate]) to the capture-date
     * ("Date taken") ordering, so media added today with an older capture date sorts into today.
     * All other sort fields are unaffected and fall back to [comparator].
     */
    fun comparator(today: LocalDate, zone: ZoneId): Comparator<MediaItem> {
        if (field != SortField.DATE_TAKEN) return comparator()
        val base: Comparator<MediaItem> = compareBy { GalleryDate.of(it, today, zone) }
        val directed = if (ascending) base else base.reversed()
        return directed.thenComparator { a, b -> if (ascending) a.id.compareTo(b.id) else b.id.compareTo(a.id) }
    }

    /**
     * Grouping timestamp matching [comparator] (same [today]/[zone]): the capture-date ordering is
     * boosted via [GalleryDate]; Date added / Date modified are returned unchanged. Using this for
     * day-grouping keeps section headers in lockstep with the sort order.
     */
    fun dateOf(item: MediaItem, today: LocalDate, zone: ZoneId): Long =
        if (field == SortField.DATE_TAKEN) GalleryDate.of(item, today, zone) else dateOf(item)
}

data class FilterSpec(
    val types: Set<MediaType> = MediaType.entries.toSet(),
    val favoritesOnly: Boolean = false,
    val minSizeBytes: Long = 0L,
    val maxSizeBytes: Long = Long.MAX_VALUE,
    val fromDate: Long = Long.MIN_VALUE,
    val toDate: Long = Long.MAX_VALUE,
    val includeHiddenBuckets: Boolean = false,
    val hiddenBuckets: Set<Long> = emptySet(),
) {
    val isDefault: Boolean
        get() = types.size == MediaType.entries.size && !favoritesOnly && minSizeBytes == 0L &&
            maxSizeBytes == Long.MAX_VALUE && fromDate == Long.MIN_VALUE && toDate == Long.MAX_VALUE

    fun matches(item: MediaItem): Boolean {
        if (item.isTrashed) return false
        if (item.type !in types) return false
        if (favoritesOnly && !item.isFavorite) return false
        if (item.sizeBytes < minSizeBytes || item.sizeBytes > maxSizeBytes) return false
        val d = item.effectiveDate
        if (d < fromDate || d > toDate) return false
        if (!includeHiddenBuckets && item.bucketId in hiddenBuckets) return false
        return true
    }
}

fun List<MediaItem>.applyFilterAndSort(filter: FilterSpec, sort: SortSpec): List<MediaItem> =
    asSequence().filter(filter::matches).sortedWith(sort.comparator()).toList()

/**
 * As [applyFilterAndSort], but the capture-date ordering surfaces media added today under today
 * (see [GalleryDate]). Other sort fields behave identically to the plain overload.
 */
fun List<MediaItem>.applyFilterAndSort(filter: FilterSpec, sort: SortSpec, today: LocalDate, zone: ZoneId): List<MediaItem> =
    asSequence().filter(filter::matches).sortedWith(sort.comparator(today, zone)).toList()
