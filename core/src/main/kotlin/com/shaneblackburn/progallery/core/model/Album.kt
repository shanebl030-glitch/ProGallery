package com.shaneblackburn.progallery.core.model

/**
 * A folder of media ("bucket" in MediaStore terms).
 */
data class Album(
    val bucketId: Long,
    val name: String,
    val relativePath: String,
    val count: Int,
    val imageCount: Int,
    val videoCount: Int,
    val totalBytes: Long,
    val cover: MediaItem?,
    val latestDate: Long,
    val isHidden: Boolean = false,
    val isPinned: Boolean = false,
)

enum class AlbumSort { RECENT, NAME, SIZE, COUNT }

object AlbumBuilder {

    /** Folders that most users expect at the top of the albums screen. */
    private val PRIORITY_PATHS = listOf("dcim/camera/", "pictures/screenshots/", "dcim/screenshots/")

    /**
     * Groups [items] into albums. Trashed items are excluded. Each album's cover is its
     * newest item. Hidden albums are flagged rather than dropped so the caller decides
     * whether to show them.
     */
    fun build(
        items: List<MediaItem>,
        hiddenBuckets: Set<Long> = emptySet(),
        pinnedBuckets: Set<Long> = emptySet(),
        sort: AlbumSort = AlbumSort.RECENT,
    ): List<Album> {
        val albums = items.asSequence()
            .filterNot { it.isTrashed }
            .groupBy { it.bucketId }
            .map { (bucketId, group) ->
                val newest = group.maxWith(compareBy<MediaItem> { it.effectiveDate }.thenBy { it.id })
                Album(
                    bucketId = bucketId,
                    name = group.first().bucketName.ifBlank { "Unnamed" },
                    relativePath = group.first().relativePath,
                    count = group.size,
                    imageCount = group.count { it.isImage },
                    videoCount = group.count { it.isVideo },
                    totalBytes = group.sumOf { it.sizeBytes },
                    cover = newest,
                    latestDate = newest.effectiveDate,
                    isHidden = bucketId in hiddenBuckets,
                    isPinned = bucketId in pinnedBuckets,
                )
            }
        return sortAlbums(albums, sort)
    }

    fun sortAlbums(albums: List<Album>, sort: AlbumSort): List<Album> {
        val bySort: Comparator<Album> = when (sort) {
            AlbumSort.RECENT -> compareByDescending<Album> { it.latestDate }
            AlbumSort.NAME -> Comparator { a, b -> NaturalOrder.compare(a.name, b.name) }
            AlbumSort.SIZE -> compareByDescending { it.totalBytes }
            AlbumSort.COUNT -> compareByDescending { it.count }
        }
        val comparator = compareByDescending<Album> { it.isPinned }
            .thenBy { priorityRank(it) }
            .then(bySort)
            .thenBy { it.bucketId }
        return albums.sortedWith(comparator)
    }

    private fun priorityRank(album: Album): Int {
        val idx = PRIORITY_PATHS.indexOf(album.relativePath.lowercase())
        return if (idx >= 0) idx else PRIORITY_PATHS.size
    }
}
