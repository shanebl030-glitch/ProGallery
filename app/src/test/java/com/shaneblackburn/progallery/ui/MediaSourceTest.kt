package com.shaneblackburn.progallery.ui

import com.shaneblackburn.progallery.core.query.SmartCollection
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSourceTest {

    @Test fun keysRoundTrip() {
        val sources = listOf(
            MediaSource.Timeline, MediaSource.Search, MediaSource.Trash, MediaSource.AlbumSrc(-123456789L),
        ) + SmartCollection.entries.map { MediaSource.Smart(it) }
        sources.forEach { assertEquals(it, MediaSource.parse(it.key), it.key) }
    }

    @Test fun unknownKeysFallBackToTimeline() {
        assertEquals(MediaSource.Timeline, MediaSource.parse(""))
        assertEquals(MediaSource.Timeline, MediaSource.parse("album:notanumber"))
        assertEquals(MediaSource.Timeline, MediaSource.parse("smart:NOPE"))
        assertEquals(MediaSource.Timeline, MediaSource.parse("garbage"))
    }
}
