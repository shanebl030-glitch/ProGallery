package com.shaneblackburn.progallery.core.util

/**
 * Most-recently-used list of ids with a fixed capacity. Immutable; every operation
 * returns a new list. Serialises to a compact comma-separated string for DataStore.
 */
data class RecentList(val ids: List<Long> = emptyList(), val capacity: Int = DEFAULT_CAPACITY) {

    fun touch(id: Long): RecentList {
        val next = ArrayList<Long>(minOf(ids.size + 1, capacity))
        next.add(id)
        for (existing in ids) {
            if (existing != id && next.size < capacity) next.add(existing)
        }
        return copy(ids = next)
    }

    fun remove(toRemove: Set<Long>): RecentList = copy(ids = ids.filterNot { it in toRemove })

    /** Drops ids that no longer exist on the device. */
    fun retainExisting(existing: Set<Long>): RecentList = copy(ids = ids.filter { it in existing })

    fun serialise(): String = ids.joinToString(",")

    companion object {
        const val DEFAULT_CAPACITY = 300

        fun parse(raw: String?, capacity: Int = DEFAULT_CAPACITY): RecentList {
            if (raw.isNullOrBlank()) return RecentList(capacity = capacity)
            val ids = raw.split(',').mapNotNull { it.trim().toLongOrNull() }.distinct().take(capacity)
            return RecentList(ids, capacity)
        }
    }
}
