package com.opentelly.tv.data.backup

import com.opentelly.tv.data.db.*
import com.opentelly.tv.data.settings.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupState(
    val version: Int,
    val playlists: List<PlaylistEntity>,
    val guides: List<EpgSourceEntity>,
    val favorites: List<FavoriteEntity>,
    val history: List<WatchHistoryEntity>,
    val blocks: Set<String>,
    val settings: AppSettings,
)

object BackupCodec {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    fun encode(state: BackupState): String = json.encodeToString(state)
    fun decode(text: String): BackupState = json.decodeFromString<BackupState>(text).also { state ->
        require(state.version == 1) { "Unsupported backup version: ${state.version}" }
        val ids = state.playlists.map { it.id }.toSet()
        require(ids.size == state.playlists.size && ids.all { it > 0 }) { "Invalid playlist IDs" }
        require(state.guides.all { it.playlistId == null || it.playlistId in ids } &&
            state.favorites.all { it.playlistId in ids } && state.history.all { it.playlistId in ids }) { "Invalid playlist references" }
        require(state.settings.panelOpacity in 60..100 && state.settings.recordPadBefore in 0..30 &&
            state.settings.recordPadAfter in 0..30 && state.settings.instantRecordMinutes in 1..1440 &&
            state.settings.reminderMinutes in 0..60 && state.settings.reminderPopupSeconds in 1..600) { "Invalid settings" }
        require(state.playlists.all { it.name.isNotBlank() && it.url.isNotBlank() } && state.guides.all { it.url.isNotBlank() }) { "Missing source details" }
        require(state.guides.map { it.id }.distinct().size == state.guides.size && state.guides.all { it.id > 0 }) { "Invalid guide IDs" }
        require(state.settings.guideRefreshHours in 1..168 && state.settings.epgOffsetMinutes in -720..720) { "Invalid guide settings" }
    }
}
