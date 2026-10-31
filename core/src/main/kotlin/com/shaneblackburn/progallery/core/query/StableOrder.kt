package com.shaneblackburn.progallery.core.query

import com.shaneblackburn.progallery.core.model.MediaItem

/**
 * Keeps a list in a STABLE order across background reorders of the underlying data.
 *
 * The full-screen viewer uses this for the "Recently viewed" collection: opening or swiping to an item
 * marks it viewed, which moves it to the front of the recents list and would otherwise reshuffle the
 * list the pager is flipping through. Freezing the id order at open and re-applying it here keeps the
 * swipe order stable for the session, while deletes still drop out and genuinely new items are appended.
 *
 * Pure and order-preserving, so it is unit-tested without any Android or UI dependency.
 */
object StableOrder {

    /**
     * Returns [items] arranged to match [frozenIds]: the items whose id is in [frozenIds], in that
     * frozen order (ids no longer present are skipped), followed by any remaining items (ids not in
     * [frozenIds]) in their current order.
     */
    fun stabilize(frozenIds: List<Long>, items: List<MediaItem>): List<MediaItem> {
        if (frozenIds.isEmpty()) return items
        val byId = items.associateBy { it.id }
        val known = frozenIds.toHashSet()
        val inOrder = frozenIds.mapNotNull { byId[it] }
        val appended = items.filter { it.id !in known }
        return inOrder + appended
    }
}
