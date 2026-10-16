package com.shaneblackburn.progallery.core.query

import com.shaneblackburn.progallery.core.model.MediaCategory
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.model.MediaType
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Parsed search query. Supports free text plus simple operators:
 *
 *  - `type:photo` / `type:video` / `type:gif` / `type:raw`  (also bare words "videos", "photos")
 *  - `album:camera`           folder name contains
 *  - `ext:png`                file extension
 *  - `fav` / `is:fav`         favourites
 *  - `year:2023`, bare `2023` year
 *  - month names ("june", "jun")
 *  - `after:2024-01-31`, `before:2024-03-01`
 *  - `>10mb`, `<500kb`        size
 *  - `screenshot`, `download`, `camera`  smart categories
 *
 * Unrecognised words must all match the file name or album name (AND semantics).
 */
data class SearchQuery(
    val terms: List<String> = emptyList(),
    val types: Set<MediaType> = emptySet(),
    val gifOnly: Boolean = false,
    val rawOnly: Boolean = false,
    val albumContains: List<String> = emptyList(),
    val extensions: Set<String> = emptySet(),
    val favoritesOnly: Boolean = false,
    val years: Set<Int> = emptySet(),
    val months: Set<Month> = emptySet(),
    val after: LocalDate? = null,
    val before: LocalDate? = null,
    val minBytes: Long? = null,
    val maxBytes: Long? = null,
    val categories: Set<MediaCategory> = emptySet(),
) {
    val isEmpty: Boolean get() = this == SearchQuery()

    fun matches(item: MediaItem, zone: ZoneId): Boolean {
        if (item.isTrashed) return false
        if (types.isNotEmpty() && item.type !in types) return false
        if (gifOnly && !item.isGif) return false
        if (rawOnly && !item.isRaw) return false
        if (favoritesOnly && !item.isFavorite) return false
        if (extensions.isNotEmpty() && item.extension !in extensions) return false
        if (categories.isNotEmpty() && item.category !in categories) return false
        val album = item.bucketName.lowercase()
        if (albumContains.any { it !in album }) return false
        if (minBytes != null && item.sizeBytes <= minBytes) return false
        if (maxBytes != null && item.sizeBytes >= maxBytes) return false
        if (years.isNotEmpty() || months.isNotEmpty() || after != null || before != null) {
            val date = java.time.Instant.ofEpochMilli(item.effectiveDate).atZone(zone).toLocalDate()
            if (years.isNotEmpty() && date.year !in years) return false
            if (months.isNotEmpty() && date.month !in months) return false
            if (after != null && !date.isAfter(after)) return false
            if (before != null && !date.isBefore(before)) return false
        }
        if (terms.isNotEmpty()) {
            val name = item.name.lowercase()
            if (!terms.all { it in name || it in album }) return false
        }
        return true
    }

    companion object {
        private val SIZE_RE = Regex("""^([<>])(\d+(?:\.\d+)?)(b|kb|mb|gb)?$""")
        private val YEAR_RE = Regex("""^(19|20)\d{2}$""")

        fun parse(raw: String, locale: Locale = Locale.ENGLISH): SearchQuery {
            var q = SearchQuery()
            val terms = mutableListOf<String>()
            val monthNames = buildMonthNames(locale)
            for (tokenRaw in tokenize(raw)) {
                val token = tokenRaw.lowercase()
                val colon = token.indexOf(':')
                if (colon > 0 && colon < token.length - 1) {
                    val key = token.substring(0, colon)
                    val value = token.substring(colon + 1)
                    val handled = when (key) {
                        "type", "is" -> applyType(value, q)?.also { q = it } != null
                        "album", "folder", "in" -> { q = q.copy(albumContains = q.albumContains + value); true }
                        "ext" -> { q = q.copy(extensions = q.extensions + value.trimStart('.')); true }
                        "year" -> value.toIntOrNull()?.let { q = q.copy(years = q.years + it) } != null
                        "after" -> parseDate(value)?.let { q = q.copy(after = it) } != null
                        "before" -> parseDate(value)?.let { q = q.copy(before = it) } != null
                        else -> false
                    }
                    if (handled) continue
                }
                val size = SIZE_RE.matchEntire(token)
                if (size != null) {
                    val bytes = sizeToBytes(size.groupValues[2].toDouble(), size.groupValues[3])
                    q = if (size.groupValues[1] == ">") q.copy(minBytes = bytes) else q.copy(maxBytes = bytes)
                    continue
                }
                if (YEAR_RE.matches(token)) { q = q.copy(years = q.years + token.toInt()); continue }
                val month = monthNames[token]
                if (month != null) { q = q.copy(months = q.months + month); continue }
                val typed = applyType(token, q)
                if (typed != null) { q = typed; continue }
                when (token) {
                    "fav", "favs", "favorite", "favorites", "favourite", "favourites" -> {
                        q = q.copy(favoritesOnly = true); continue
                    }
                    "screenshot", "screenshots" -> { q = q.copy(categories = q.categories + MediaCategory.SCREENSHOT); continue }
                    "download", "downloads" -> { q = q.copy(categories = q.categories + MediaCategory.DOWNLOAD); continue }
                    "camera" -> { q = q.copy(categories = q.categories + MediaCategory.CAMERA); continue }
                    "recording", "recordings" -> {
                        q = q.copy(categories = q.categories + MediaCategory.SCREEN_RECORDING); continue
                    }
                }
                if (token.isNotBlank()) terms += token
            }
            return q.copy(terms = terms)
        }

        private fun applyType(value: String, q: SearchQuery): SearchQuery? = when (value) {
            "photo", "photos", "image", "images", "picture", "pictures" -> q.copy(types = q.types + MediaType.IMAGE)
            "video", "videos", "movie", "movies" -> q.copy(types = q.types + MediaType.VIDEO)
            "gif", "gifs" -> q.copy(gifOnly = true)
            "raw" -> q.copy(rawOnly = true)
            "fav", "favorite", "favourite" -> q.copy(favoritesOnly = true)
            else -> null
        }

        /** Splits on whitespace but keeps "quoted phrases" together. */
        fun tokenize(raw: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuotes = false
            for (c in raw) {
                when {
                    c == '"' -> { inQuotes = !inQuotes }
                    c.isWhitespace() && !inQuotes -> { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
                    else -> sb.append(c)
                }
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }

        private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

        private fun sizeToBytes(n: Double, unit: String): Long = (n * when (unit) {
            "kb" -> 1_000.0
            "mb" -> 1_000_000.0
            "gb" -> 1_000_000_000.0
            else -> 1.0
        }).toLong()

        private fun buildMonthNames(locale: Locale): Map<String, Month> {
            val map = HashMap<String, Month>()
            for (m in Month.entries) {
                for (l in setOf(locale, Locale.ENGLISH)) {
                    map[m.getDisplayName(TextStyle.FULL, l).lowercase(l)] = m
                    map[m.getDisplayName(TextStyle.SHORT, l).lowercase(l).trimEnd('.')] = m
                }
            }
            return map
        }
    }
}
