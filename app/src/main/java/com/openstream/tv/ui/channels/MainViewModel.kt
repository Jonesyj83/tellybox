package com.openstream.tv.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openstream.tv.AppContainer
import com.openstream.tv.data.db.ChannelEntity
import com.openstream.tv.data.db.GroupWithCount
import com.openstream.tv.data.db.PlaylistEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An entry in the left-hand column. The first three are built in, the rest come from playlists. */
sealed interface GroupSelection {
    data object Favorites : GroupSelection
    data object Recent : GroupSelection
    data object All : GroupSelection
    data class Search(val query: String) : GroupSelection
    data class Group(val id: Long) : GroupSelection
}

data class GroupRow(val selection: GroupSelection, val name: String, val count: Int?)

data class ChannelRow(
    val channel: ChannelEntity,
    val nowPlaying: String?,
    val favorite: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val app: AppContainer) : ViewModel() {

    val playlists: StateFlow<List<PlaylistEntity>?> = app.playlists.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val searchQuery = MutableStateFlow<String?>(null)

    val groups: StateFlow<List<GroupWithCount>> = app.channels.observeGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selection = MutableStateFlow<GroupSelection>(GroupSelection.All)
    val selection: StateFlow<GroupSelection> = _selection

    val favoriteKeys: StateFlow<Set<Pair<Long, String>>> = app.channels.observeFavorites()
        .map { list -> list.mapTo(HashSet()) { it.playlistId to it.channelKey } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private fun channelsFor(sel: GroupSelection): Flow<List<ChannelEntity>> = when (sel) {
        GroupSelection.Favorites -> app.channels.observeFavorites()
        GroupSelection.Recent -> app.channels.observeRecent()
        GroupSelection.All -> app.channels.observeAll()
        is GroupSelection.Search -> flow { emit(app.channels.search(sel.query)) }
        is GroupSelection.Group -> app.channels.observeGroup(sel.id)
    }

    /** Changes whenever a guide import finishes, so now-playing gets looked up again. */
    private val epgVersion = app.epg.observeSources()
        .map { sources -> sources.sumOf { it.lastUpdated } }
        .distinctUntilChanged()

    val channels: StateFlow<List<ChannelRow>?> = _selection
        .flatMapLatest { channelsFor(it) }
        .combine(epgVersion) { list, _ -> list }
        .mapLatest { list ->
            val now = app.epg.currentPrograms(list.mapNotNull { it.tvgId })
            list to now
        }
        .combine(favoriteKeys) { (list, now), favs ->
            list.map { c ->
                ChannelRow(
                    channel = c,
                    nowPlaying = c.tvgId?.let { now[it.lowercase()]?.title },
                    favorite = (c.playlistId to c.channelKey) in favs,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Synthetic entries first, then the playlist groups. */
    fun groupRows(groups: List<GroupWithCount>, search: String?): List<GroupRow> = buildList {
        if (search != null) add(GroupRow(GroupSelection.Search(search), search, null))
        add(GroupRow(GroupSelection.Favorites, "", null))
        add(GroupRow(GroupSelection.Recent, "", null))
        add(GroupRow(GroupSelection.All, "", groups.sumOf { it.channelCount }))
        groups.forEach { add(GroupRow(GroupSelection.Group(it.id), it.name, it.channelCount)) }
    }

    val search: StateFlow<String?> = searchQuery

    fun select(selection: GroupSelection) {
        if (_selection.value != selection) _selection.value = selection
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            searchQuery.value = null
            if (_selection.value is GroupSelection.Search) _selection.value = GroupSelection.All
            return
        }
        searchQuery.value = q
        _selection.value = GroupSelection.Search(q)
    }

    fun toggleFavorite(channel: ChannelEntity, done: (Boolean) -> Unit) {
        viewModelScope.launch { done(app.channels.toggleFavorite(channel)) }
    }
}
