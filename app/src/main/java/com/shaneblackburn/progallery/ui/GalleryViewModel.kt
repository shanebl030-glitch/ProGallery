package com.shaneblackburn.progallery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shaneblackburn.progallery.ProGalleryApp
import com.shaneblackburn.progallery.core.model.Album
import com.shaneblackburn.progallery.core.model.AlbumBuilder
import com.shaneblackburn.progallery.core.model.AlbumSort
import com.shaneblackburn.progallery.core.model.MediaItem
import com.shaneblackburn.progallery.core.query.DateGrouper
import com.shaneblackburn.progallery.core.query.FilterSpec
import com.shaneblackburn.progallery.core.query.GridEntry
import com.shaneblackburn.progallery.core.query.GroupBy
import com.shaneblackburn.progallery.core.query.SearchQuery
import com.shaneblackburn.progallery.core.query.SmartCollection
import com.shaneblackburn.progallery.core.query.SmartCollections
import com.shaneblackburn.progallery.core.query.SortSpec
import com.shaneblackburn.progallery.core.query.applyFilterAndSort
import com.shaneblackburn.progallery.data.ActionResult
import com.shaneblackburn.progallery.data.AppSettings
import com.shaneblackburn.progallery.data.LoadResult
import com.shaneblackburn.progallery.data.MediaDetails
import com.shaneblackburn.progallery.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Where a list of media came from; lets the viewer re-derive its list live. */
sealed interface MediaSource {
    val key: String

    data object Timeline : MediaSource { override val key = "timeline" }
    data class AlbumSrc(val bucketId: Long) : MediaSource { override val key = "album:$bucketId" }
    data class Smart(val collection: SmartCollection) : MediaSource { override val key = "smart:${collection.name}" }
    data object Search : MediaSource { override val key = "search" }
    data object Trash : MediaSource { override val key = "trash" }

    companion object {
        fun parse(key: String): MediaSource = when {
            key == "timeline" -> Timeline
            key == "search" -> Search
            key == "trash" -> Trash
            key.startsWith("album:") -> key.removePrefix("album:").toLongOrNull()?.let { AlbumSrc(it) } ?: Timeline
            key.startsWith("smart:") -> SmartCollection.entries.firstOrNull { it.name == key.removePrefix("smart:") }
                ?.let { Smart(it) } ?: Timeline
            else -> Timeline
        }
    }
}

sealed interface LibraryState {
    data object Loading : LibraryState
    data object NeedsPermission : LibraryState
    data class Error(val message: String) : LibraryState
    data class Ready(val all: List<MediaItem>) : LibraryState
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    /** UI must launch the system consent dialog and report back via [GalleryViewModel.onConsentResult]. */
    data class Consent(val request: ActionResult.NeedsConsent, val successMessage: String) : UiEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ProGalleryApp
    private val settingsRepo = app.settings
    private val actions = app.actions

    val supportsTrash: Boolean get() = actions.supportsTrash

    private val permissionGranted = MutableStateFlow<Boolean?>(null)
    private val reloadTick = MutableStateFlow(0)

    /** Hidden albums are revealed only after device authentication, per session. */
    private val _hiddenUnlocked = MutableStateFlow(false)
    val hiddenUnlocked: StateFlow<Boolean> = _hiddenUnlocked

    val settings: StateFlow<AppSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val raw: Flow<LibraryState> = combine(permissionGranted, reloadTick) { granted, _ -> granted }
        .flatMapLatest { granted ->
            when (granted) {
                null -> kotlinx.coroutines.flow.flowOf(LibraryState.Loading)
                false -> kotlinx.coroutines.flow.flowOf(LibraryState.NeedsPermission)
                true -> app.media.observeAll().map { r ->
                    when (r) {
                        is LoadResult.Success -> LibraryState.Ready(r.items)
                        LoadResult.PermissionMissing -> LibraryState.NeedsPermission
                        is LoadResult.Error -> LibraryState.Error(r.message)
                    }
                }
            }
        }

    /** Library with app-managed favourites merged in. */
    val library: StateFlow<LibraryState> = combine(raw, settings.map { it.favoriteIds }) { state, favs ->
        if (state is LibraryState.Ready && favs.isNotEmpty()) {
            LibraryState.Ready(state.all.map { if (it.id in favs) it.copy(isFavorite = true) else it })
        } else state
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState.Loading)

    private val allItems: Flow<List<MediaItem>> =
        library.map { (it as? LibraryState.Ready)?.all ?: emptyList() }

    /** Effective set of buckets to hide right now. */
    private val hiddenFilter: Flow<FilterSpec> = combine(settings, _hiddenUnlocked) { s, unlocked ->
        val reveal = s.showHidden && (unlocked || !s.requireAuthForHidden)
        FilterSpec(hiddenBuckets = s.hiddenBuckets, includeHiddenBuckets = reveal)
    }

    val timeline: StateFlow<List<MediaItem>> = combine(allItems, hiddenFilter, settings.map { it.sort }) { all, f, sort ->
        all.applyFilterAndSort(f, sort)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val timelineEntries: StateFlow<List<GridEntry>> = combine(
        timeline, settings.map { it.groupBy to it.sort },
    ) { items, (groupBy, sort) -> group(items, groupBy, sort) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val albums: StateFlow<List<Album>> = combine(allItems, settings, _hiddenUnlocked) { all, s, unlocked ->
        val built = AlbumBuilder.build(all, s.hiddenBuckets, s.pinnedBuckets, s.albumSort)
        val reveal = s.showHidden && (unlocked || !s.requireAuthForHidden)
        if (reveal) built else built.filterNot { it.isHidden }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** All albums including hidden ones (for the "Hidden albums" manager). */
    val allAlbums: StateFlow<List<Album>> = combine(allItems, settings) { all, s ->
        AlbumBuilder.build(all, s.hiddenBuckets, s.pinnedBuckets, AlbumSort.NAME)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val trash: StateFlow<List<MediaItem>> = allItems.map { all ->
        all.filter { it.isTrashed }.sortedByDescending { it.trashExpiresAt }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ---- Search ----
    val searchText = MutableStateFlow("")
    val searchResults: StateFlow<List<MediaItem>> = combine(searchText, timeline) { text, items ->
        val q = SearchQuery.parse(text)
        if (q.isEmpty) emptyList() else {
            val zone = ZoneId.systemDefault()
            items.filter { q.matches(it, zone) }
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ---- Selection ----
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    fun toggleSelect(id: Long) = _selection.update { if (id in it) it - id else it + id }
    fun selectAll(ids: Collection<Long>) = _selection.update { it + ids }
    fun deselectAll(ids: Collection<Long>) = _selection.update { it - ids.toSet() }
    fun setSelection(ids: Set<Long>) { _selection.value = ids }
    fun clearSelection() { _selection.value = emptySet() }

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    // ---- Lists by source ----

    fun itemsFor(source: MediaSource): Flow<List<MediaItem>> = when (source) {
        MediaSource.Timeline -> timeline
        MediaSource.Search -> searchResults
        MediaSource.Trash -> trash
        is MediaSource.AlbumSrc -> combine(allItems, settings) { all, s ->
            // An album is always viewable from inside, even if hidden from the main lists.
            all.applyFilterAndSort(FilterSpec(includeHiddenBuckets = true), s.sort).filter { it.bucketId == source.bucketId }
        }.flowOn(Dispatchers.Default)
        is MediaSource.Smart -> combine(allItems, hiddenFilter, settings) { all, f, s ->
            val visible = all.filter { f.matches(it) }
            val picked = SmartCollections.select(source.collection, visible, System.currentTimeMillis(), s.recent.ids)
            if (SmartCollections.hasIntrinsicOrder(source.collection)) picked else picked.sortedWith(s.sort.comparator())
        }.flowOn(Dispatchers.Default)
    }

    fun entriesFor(source: MediaSource): Flow<List<GridEntry>> = combine(itemsFor(source), settings) { items, s ->
        val intrinsic = source is MediaSource.Smart && SmartCollections.hasIntrinsicOrder(source.collection)
        if (source == MediaSource.Trash || intrinsic) group(items, GroupBy.NONE, s.sort) else group(items, s.groupBy, s.sort)
    }.flowOn(Dispatchers.Default)

    fun counts(): Flow<Map<SmartCollection, Int>> = combine(allItems, hiddenFilter, settings) { all, f, s ->
        val visible = all.filter { f.matches(it) }
        val now = System.currentTimeMillis()
        SmartCollection.entries.associateWith { SmartCollections.select(it, visible, now, s.recent.ids).size }
    }.flowOn(Dispatchers.Default)

    private fun group(items: List<MediaItem>, groupBy: GroupBy, sort: SortSpec): List<GridEntry> {
        val effective = if (sort.isDateBased) groupBy else GroupBy.NONE
        return DateGrouper(ZoneId.systemDefault(), LocalDate.now()).group(items, effective, sort::dateOf)
    }

    fun albumName(bucketId: Long): String =
        allAlbums.value.firstOrNull { it.bucketId == bucketId }?.name
            ?: (library.value as? LibraryState.Ready)?.all?.firstOrNull { it.bucketId == bucketId }?.bucketName
            ?: "Album"

    fun itemsByIds(ids: Set<Long>): List<MediaItem> =
        (library.value as? LibraryState.Ready)?.all?.filter { it.id in ids } ?: emptyList()

    // ---- Permission & loading ----

    fun onPermissionState(granted: Boolean) {
        permissionGranted.value = granted
    }

    fun reload() = reloadTick.update { it + 1 }

    // ---- Settings ----

    fun setTheme(m: ThemeMode) = launchIO { settingsRepo.setTheme(m) }
    fun setDynamicColor(on: Boolean) = launchIO { settingsRepo.setDynamicColor(on) }
    fun setGridColumns(n: Int) = launchIO { settingsRepo.setGridColumns(n) }
    fun setAlbumColumns(n: Int) = launchIO { settingsRepo.setAlbumColumns(n) }
    fun setSort(s: SortSpec) = launchIO { settingsRepo.setSort(s) }
    fun setGroupBy(g: GroupBy) = launchIO { settingsRepo.setGroupBy(g) }
    fun setAlbumSort(s: AlbumSort) = launchIO { settingsRepo.setAlbumSort(s) }
    fun setShowHidden(on: Boolean) = launchIO { settingsRepo.setShowHidden(on) }
    fun setRequireAuthForHidden(on: Boolean) = launchIO { settingsRepo.setRequireAuthForHidden(on) }
    fun setSlideshowSeconds(s: Int) = launchIO { settingsRepo.setSlideshowSeconds(s) }
    fun setAutoPlay(on: Boolean) = launchIO { settingsRepo.setAutoPlay(on) }
    fun setLoop(on: Boolean) = launchIO { settingsRepo.setLoop(on) }
    fun setConfirmTrash(on: Boolean) = launchIO { settingsRepo.setConfirmTrash(on) }
    fun clearRecent() = launchIO { settingsRepo.clearRecent() }

    fun unlockHidden() { _hiddenUnlocked.value = true }
    fun lockHidden() { _hiddenUnlocked.value = false }

    fun setAlbumsHidden(bucketIds: Collection<Long>, hidden: Boolean) = launchIO {
        settingsRepo.setHidden(bucketIds, hidden)
        message(if (hidden) "Album hidden" else "Album unhidden")
    }

    fun setAlbumsPinned(bucketIds: Collection<Long>, pinned: Boolean) = launchIO { settingsRepo.setPinned(bucketIds, pinned) }

    fun markViewed(id: Long) = launchIO { settingsRepo.touchRecent(id) }

    // ---- Media actions ----

    fun setFavorite(items: List<MediaItem>, favorite: Boolean) = launchIO {
        settingsRepo.setFavorite(items.map { it.id }, favorite)
    }

    fun toggleFavorite(item: MediaItem) = setFavorite(listOf(item), !item.isFavorite)

    fun trash(items: List<MediaItem>) = dispatch(actions.trash(items),
        if (actions.supportsTrash) "Moved ${plural(items.size)} to Recently deleted" else "Deleted ${plural(items.size)}")

    fun restore(items: List<MediaItem>) = dispatch(actions.restore(items), "Restored ${plural(items.size)}")

    fun deleteForever(items: List<MediaItem>) = dispatch(actions.deleteForever(items), "Deleted ${plural(items.size)}")

    fun rename(item: MediaItem, newName: String) = dispatch(actions.rename(item, newName), "Renamed")

    fun move(items: List<MediaItem>, relativePath: String) =
        dispatch(actions.move(items, relativePath), "Moved ${plural(items.size)}")

    fun copy(items: List<MediaItem>, relativePath: String) = launchIO {
        message("Copying ${plural(items.size)}…")
        handle(actions.copy(items, relativePath), "Copied ${plural(items.size)}")
    }

    fun shareIntent(items: List<MediaItem>) = actions.shareIntent(items)
    fun setAsIntent(item: MediaItem) = actions.setAsIntent(item)
    fun openWithIntent(item: MediaItem) = actions.openWithIntent(item)

    suspend fun details(item: MediaItem): MediaDetails = app.metadata.read(item)

    private fun dispatch(result: ActionResult, success: String) = launchIO { handle(result, success) }

    private suspend fun handle(result: ActionResult, success: String) {
        when (result) {
            is ActionResult.Done -> { if (result.count > 0) message(success); clearSelection() }
            is ActionResult.Failed -> message(result.message)
            is ActionResult.NeedsConsent -> _events.send(UiEvent.Consent(result, success))
            is ActionResult.RunNow -> handle(result.run(), success)
        }
    }

    /** Called by the UI after the system consent dialog closes. */
    fun onConsentResult(request: ActionResult.NeedsConsent, granted: Boolean, success: String) = launchIO {
        if (!granted) { message("Cancelled"); return@launchIO }
        val follow = request.afterGrant
        if (follow == null) { message(success); clearSelection() } else handle(follow(), success)
    }

    private suspend fun message(text: String) = _events.send(UiEvent.Message(text))

    private fun plural(n: Int) = if (n == 1) "1 item" else "$n items"

    private fun launchIO(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("GalleryViewModel", "action failed", e)
            message("Something went wrong: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Wait until the first real library snapshot is available (used by the external viewer). */
    suspend fun awaitReady(): List<MediaItem> =
        (library.first { it is LibraryState.Ready || it is LibraryState.Error || it is LibraryState.NeedsPermission }
            as? LibraryState.Ready)?.all ?: emptyList()

}
