package com.shaneblackburn.progallery.core.query

import com.shaneblackburn.progallery.core.model.MediaCategory
import com.shaneblackburn.progallery.core.model.MediaItem

/**
 * Built-in smart collections shown in the Library tab. Each is a pure selection over
 * the full media list so it stays consistent with sorting/filtering rules.
 */
enum class SmartCollection(val title: String) {
    FAVORITES("Favourites"),
    VIDEOS("Videos"),
    RECENTLY_ADDED("Recently added"),
    RECENTLY_VIEWED("Recently viewed"),
    CAMERA("Camera"),
    SCREENSHOTS("Screenshots"),
    SCREEN_RECORDINGS("Screen recordings"),
    DOWNLOADS("Downloads"),
    GIFS("Animated"),
    RAW("RAW"),
    MOTION("Motion photos"),
    LARGE("Large files");

    companion object {
        const val RECENTLY_ADDED_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        const val LARGE_FILE_BYTES = 100L * 1000 * 1000
    }
}

object SmartCollections {

    /**
     * @param recentIds ids in most-recent-first order (for RECENTLY_VIEWED).
     * @param now current epoch millis (parameter for testability).
     */
    fun select(
        collection: SmartCollection,
        items: List<MediaItem>,
        now: Long,
        recentIds: List<Long> = emptyList(),
    ): List<MediaItem> {
        val live = items.filterNot { it.isTrashed }
        return when (collection) {
            SmartCollection.FAVORITES -> live.filter { it.isFavorite }
            SmartCollection.VIDEOS -> live.filter { it.isVideo }
            SmartCollection.RECENTLY_ADDED -> live
                .filter { now - it.dateAdded <= SmartCollection.RECENTLY_ADDED_WINDOW_MS }
                .sortedWith(compareByDescending<MediaItem> { it.dateAdded }.thenByDescending { it.id })
            SmartCollection.RECENTLY_VIEWED -> {
                val byId = live.associateBy { it.id }
                recentIds.mapNotNull { byId[it] }
            }
            SmartCollection.CAMERA -> live.filter { it.category == MediaCategory.CAMERA }
            SmartCollection.SCREENSHOTS -> live.filter { it.category == MediaCategory.SCREENSHOT }
            SmartCollection.SCREEN_RECORDINGS -> live.filter { it.category == MediaCategory.SCREEN_RECORDING }
            SmartCollection.DOWNLOADS -> live.filter { it.category == MediaCategory.DOWNLOAD }
            SmartCollection.GIFS -> live.filter { it.isGif }
            SmartCollection.RAW -> live.filter { it.isRaw }
            SmartCollection.MOTION -> live.filter { it.isMotionPhotoCandidate }
            SmartCollection.LARGE -> live.filter { it.sizeBytes >= SmartCollection.LARGE_FILE_BYTES }
                .sortedByDescending { it.sizeBytes }
        }
    }

    /** True when the collection keeps its own ordering instead of the user's sort. */
    fun hasIntrinsicOrder(collection: SmartCollection): Boolean =
        collection == SmartCollection.RECENTLY_VIEWED || collection == SmartCollection.RECENTLY_ADDED ||
            collection == SmartCollection.LARGE
}
