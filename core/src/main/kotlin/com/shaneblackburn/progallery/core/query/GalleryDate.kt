package com.shaneblackburn.progallery.core.query

import com.shaneblackburn.progallery.core.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The date used to PLACE an item in the day-grouped timeline ("Today", "Yesterday", "12 March"…).
 *
 * Normally this is the capture date ([MediaItem.effectiveDate]) so photos and videos sit under the
 * day they were taken. The one exception is "recently added" media: an item that was ADDED to the
 * device today, but whose capture date is OLDER than today, is surfaced under **Today** using its
 * add time. That covers an edited/exported video — which deliberately keeps its original footage's
 * capture date — as well as a photo transferred or downloaded today: it appears as new on the day it
 * arrives, then the next day falls back to its capture-date position, so the library's normal
 * chronology is preserved and nothing shows up twice.
 *
 * This NEVER changes any stored date. It only chooses which existing timestamp drives day-grouping
 * and the date sort, and it is applied ONLY to the capture-date ("Date taken") ordering — sorting
 * explicitly by Date added or Date modified already orders by those fields, so no boost is needed or
 * applied there.
 *
 * Pure and deterministic: [today] and [zone] are parameters so it is unit-testable without a clock.
 */
object GalleryDate {

    /** Grouping/sort timestamp for [item]: its add time when it was added today with an older
     *  capture date, otherwise its capture date. See the class doc for the rationale. */
    fun of(item: MediaItem, today: LocalDate, zone: ZoneId): Long {
        val added = item.dateAdded
        if (added <= 0L) return item.effectiveDate
        // Only items added *today* are candidates for the boost.
        if (localDate(added, zone) != today) return item.effectiveDate
        // Boost only when the capture date is genuinely older than today; same-day captures (and any
        // future-dated metadata) keep their own effectiveDate so normal chronology is untouched.
        val capturedDate = localDate(item.effectiveDate, zone)
        return if (capturedDate.isBefore(today)) added else item.effectiveDate
    }

    private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
