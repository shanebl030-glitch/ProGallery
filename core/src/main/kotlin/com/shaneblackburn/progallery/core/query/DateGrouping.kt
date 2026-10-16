package com.shaneblackburn.progallery.core.query

import com.shaneblackburn.progallery.core.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class GroupBy { DAY, MONTH, YEAR, NONE }

/** One entry in a sectioned grid: either a header or a media item. */
sealed interface GridEntry {
    val key: String

    data class Header(val label: String, val sectionKey: String, val itemIds: List<Long>) : GridEntry {
        override val key: String get() = "h_$sectionKey"
    }

    data class Item(val item: MediaItem, val index: Int) : GridEntry {
        override val key: String get() = "m_${item.id}"
    }
}

/**
 * Produces section headers ("Today", "Yesterday", "Saturday", "12 March", "March 2023")
 * for a list that is already sorted by date. Pure function of its inputs: [today] and
 * [zone] are parameters so tests are deterministic.
 */
class DateGrouper(
    private val zone: ZoneId,
    private val today: LocalDate,
    private val locale: Locale = Locale.getDefault(),
) {
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", locale)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)

    fun localDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    fun sectionKey(epochMillis: Long, groupBy: GroupBy): String {
        val d = localDate(epochMillis)
        return when (groupBy) {
            GroupBy.DAY -> d.toString()
            GroupBy.MONTH -> YearMonth.from(d).toString()
            GroupBy.YEAR -> d.year.toString()
            GroupBy.NONE -> "all"
        }
    }

    fun label(epochMillis: Long, groupBy: GroupBy): String {
        val d = localDate(epochMillis)
        return when (groupBy) {
            GroupBy.DAY -> dayLabel(d)
            GroupBy.MONTH -> {
                val month = d.month.getDisplayName(TextStyle.FULL, locale)
                if (d.year == today.year) month else "$month ${d.year}"
            }
            GroupBy.YEAR -> d.year.toString()
            GroupBy.NONE -> ""
        }
    }

    fun dayLabel(d: LocalDate): String {
        val diff = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        return when {
            diff == 0L -> "Today"
            diff == 1L -> "Yesterday"
            diff in 2..6 -> d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            d.year == today.year -> d.format(dayMonth)
            else -> d.format(dayMonthYear)
        }
    }

    /**
     * Interleaves headers with items. [dateOf] picks the timestamp used for grouping
     * (it must match the field the list was sorted by).
     */
    fun group(items: List<MediaItem>, groupBy: GroupBy, dateOf: (MediaItem) -> Long = { it.effectiveDate }): List<GridEntry> {
        if (groupBy == GroupBy.NONE) return items.mapIndexed { i, m -> GridEntry.Item(m, i) }
        val out = ArrayList<GridEntry>(items.size + items.size / 8 + 1)
        var currentKey: String? = null
        var headerIndex = -1
        var ids = ArrayList<Long>()
        items.forEachIndexed { i, m ->
            val t = dateOf(m)
            val key = sectionKey(t, groupBy)
            if (key != currentKey) {
                if (headerIndex >= 0) {
                    out[headerIndex] = (out[headerIndex] as GridEntry.Header).copy(itemIds = ids)
                }
                ids = ArrayList()
                currentKey = key
                headerIndex = out.size
                out.add(GridEntry.Header(label(t, groupBy), key, emptyList()))
            }
            ids.add(m.id)
            out.add(GridEntry.Item(m, i))
        }
        if (headerIndex >= 0) out[headerIndex] = (out[headerIndex] as GridEntry.Header).copy(itemIds = ids)
        return out
    }
}
