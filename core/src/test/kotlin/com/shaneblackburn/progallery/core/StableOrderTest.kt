package com.shaneblackburn.progallery.core

import com.shaneblackburn.progallery.core.TestData.item
import com.shaneblackburn.progallery.core.query.StableOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fix: the viewer keeps a stable order for "Recently viewed" so that marking items viewed (which bumps
 * the viewed item to the front of the recents list) no longer reshuffles the pager under the user.
 */
class StableOrderTest {

    private fun ids(vararg id: Long) = id.map { item(it) }
    private fun idsOf(items: List<com.shaneblackburn.progallery.core.model.MediaItem>) = items.map { it.id }

    @Test fun keepsFrozenOrderWhenTheLiveListReshuffles() {
        val frozen = listOf(10L, 20L, 30L, 40L)
        // Live list has been reordered (40 bumped to the front, as marking it viewed would do).
        val live = ids(40, 10, 20, 30)
        assertEquals(listOf(10L, 20L, 30L, 40L), idsOf(StableOrder.stabilize(frozen, live)),
            "the viewer keeps the order it opened with, regardless of how the live list reordered")
    }

    @Test fun dropsItemsThatNoLongerExist() {
        val frozen = listOf(10L, 20L, 30L, 40L)
        val live = ids(10, 30) // 20 and 40 deleted
        assertEquals(listOf(10L, 30L), idsOf(StableOrder.stabilize(frozen, live)))
    }

    @Test fun appendsGenuinelyNewItemsAtTheEndInLiveOrder() {
        val frozen = listOf(10L, 20L)
        val live = ids(99, 20, 10, 77) // 99 and 77 are new, in this live order
        assertEquals(listOf(10L, 20L, 99L, 77L), idsOf(StableOrder.stabilize(frozen, live)),
            "frozen items first in frozen order, then new items in their live order")
    }

    @Test fun emptyFrozenOrderReturnsLiveListUnchanged() {
        val live = ids(3, 1, 2)
        assertEquals(listOf(3L, 1L, 2L), idsOf(StableOrder.stabilize(emptyList(), live)))
    }

    @Test fun handlesEmptyLiveList() {
        assertEquals(emptyList(), StableOrder.stabilize(listOf(1L, 2L), emptyList()))
    }
}
