package com.shaneblackburn.progallery.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shaneblackburn.progallery.core.model.AlbumSort
import com.shaneblackburn.progallery.core.query.GroupBy
import com.shaneblackburn.progallery.core.query.SortSpec
import com.shaneblackburn.progallery.core.util.RecentList
import com.shaneblackburn.progallery.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Everything the user can configure, as one immutable snapshot. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val gridColumns: Int = 4,
    val albumColumns: Int = 2,
    val sort: SortSpec = SortSpec(),
    val groupBy: GroupBy = GroupBy.DAY,
    val albumSort: AlbumSort = AlbumSort.RECENT,
    val hiddenBuckets: Set<Long> = emptySet(),
    val pinnedBuckets: Set<Long> = emptySet(),
    val favoriteIds: Set<Long> = emptySet(),
    val recent: RecentList = RecentList(),
    val showHidden: Boolean = false,
    val requireAuthForHidden: Boolean = true,
    val slideshowSeconds: Int = 4,
    val autoPlayVideos: Boolean = true,
    val loopVideos: Boolean = false,
    val confirmTrash: Boolean = true,
) {
    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 8
    }
}

/**
 * DataStore-backed settings. Corrupt or unreadable files fall back to defaults instead
 * of crashing the app.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val columns = intPreferencesKey("grid_columns")
        val albumColumns = intPreferencesKey("album_columns")
        val sortField = stringPreferencesKey("sort_field")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val groupBy = stringPreferencesKey("group_by")
        val albumSort = stringPreferencesKey("album_sort")
        val hidden = stringSetPreferencesKey("hidden_buckets")
        val pinned = stringSetPreferencesKey("pinned_buckets")
        val favorites = stringSetPreferencesKey("favorites")
        val recent = stringPreferencesKey("recent_ids")
        val showHidden = booleanPreferencesKey("show_hidden")
        val authHidden = booleanPreferencesKey("auth_hidden")
        val slideshow = intPreferencesKey("slideshow_seconds")
        val autoPlay = booleanPreferencesKey("autoplay_videos")
        val loop = booleanPreferencesKey("loop_videos")
        val confirmTrash = booleanPreferencesKey("confirm_trash")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { p ->
            val d = AppSettings()
            AppSettings(
                themeMode = enumOr(p[Keys.theme], d.themeMode),
                dynamicColor = p[Keys.dynamic] ?: d.dynamicColor,
                gridColumns = (p[Keys.columns] ?: d.gridColumns).coerceIn(AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS),
                albumColumns = (p[Keys.albumColumns] ?: d.albumColumns).coerceIn(2, 4),
                sort = SortSpec(enumOr(p[Keys.sortField], d.sort.field), p[Keys.sortAsc] ?: d.sort.ascending),
                groupBy = enumOr(p[Keys.groupBy], d.groupBy),
                albumSort = enumOr(p[Keys.albumSort], d.albumSort),
                hiddenBuckets = p[Keys.hidden].toLongSet(),
                pinnedBuckets = p[Keys.pinned].toLongSet(),
                favoriteIds = p[Keys.favorites].toLongSet(),
                recent = RecentList.parse(p[Keys.recent]),
                showHidden = p[Keys.showHidden] ?: d.showHidden,
                requireAuthForHidden = p[Keys.authHidden] ?: d.requireAuthForHidden,
                slideshowSeconds = (p[Keys.slideshow] ?: d.slideshowSeconds).coerceIn(1, 30),
                autoPlayVideos = p[Keys.autoPlay] ?: d.autoPlayVideos,
                loopVideos = p[Keys.loop] ?: d.loopVideos,
                confirmTrash = p[Keys.confirmTrash] ?: d.confirmTrash,
            )
        }
        .distinctUntilChanged()

    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = edit { it[Keys.dynamic] = on }
    suspend fun setGridColumns(n: Int) =
        edit { it[Keys.columns] = n.coerceIn(AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS) }
    suspend fun setAlbumColumns(n: Int) = edit { it[Keys.albumColumns] = n.coerceIn(2, 4) }
    suspend fun setSort(sort: SortSpec) = edit {
        it[Keys.sortField] = sort.field.name
        it[Keys.sortAsc] = sort.ascending
    }
    suspend fun setGroupBy(g: GroupBy) = edit { it[Keys.groupBy] = g.name }
    suspend fun setAlbumSort(s: AlbumSort) = edit { it[Keys.albumSort] = s.name }
    suspend fun setShowHidden(on: Boolean) = edit { it[Keys.showHidden] = on }
    suspend fun setRequireAuthForHidden(on: Boolean) = edit { it[Keys.authHidden] = on }
    suspend fun setSlideshowSeconds(s: Int) = edit { it[Keys.slideshow] = s.coerceIn(1, 30) }
    suspend fun setAutoPlay(on: Boolean) = edit { it[Keys.autoPlay] = on }
    suspend fun setLoop(on: Boolean) = edit { it[Keys.loop] = on }
    suspend fun setConfirmTrash(on: Boolean) = edit { it[Keys.confirmTrash] = on }

    suspend fun setHidden(bucketIds: Collection<Long>, hidden: Boolean) =
        toggleSet(Keys.hidden, bucketIds, hidden)

    suspend fun setPinned(bucketIds: Collection<Long>, pinned: Boolean) =
        toggleSet(Keys.pinned, bucketIds, pinned)

    suspend fun setFavorite(ids: Collection<Long>, favorite: Boolean) =
        toggleSet(Keys.favorites, ids, favorite)

    suspend fun touchRecent(id: Long) = edit {
        it[Keys.recent] = RecentList.parse(it[Keys.recent]).touch(id).serialise()
    }

    suspend fun clearRecent() = edit { it.remove(Keys.recent) }

    /** Removes ids of media that no longer exist so the stored sets don't grow forever. */
    suspend fun pruneMissing(existingIds: Set<Long>) = edit { p ->
        p[Keys.favorites]?.let { fav -> p[Keys.favorites] = fav.filterTo(HashSet()) { s -> s.toLongOrNull() in existingIds } }
        p[Keys.recent]?.let { r -> p[Keys.recent] = RecentList.parse(r).retainExisting(existingIds).serialise() }
    }

    private suspend fun toggleSet(key: Preferences.Key<Set<String>>, ids: Collection<Long>, add: Boolean) = edit { p ->
        val current = p[key].orEmpty().toMutableSet()
        val strs = ids.map { it.toString() }
        if (add) current.addAll(strs) else current.removeAll(strs.toSet())
        p[key] = current
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            context.dataStore.edit { block(it) }
        } catch (e: IOException) {
            android.util.Log.w("Settings", "Failed to save settings", e)
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun Set<String>?.toLongSet(): Set<Long> = this?.mapNotNullTo(HashSet()) { it.toLongOrNull() } ?: emptySet()
}

