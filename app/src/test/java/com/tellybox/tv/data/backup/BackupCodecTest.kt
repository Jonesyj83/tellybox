package com.tellybox.tv.data.backup

import com.tellybox.tv.data.db.*
import com.tellybox.tv.data.settings.*
import com.tellybox.tv.player.BufferSize
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private val state = BackupState(
        version = 1,
        playlists = listOf(PlaylistEntity(id = 7, name = "TV \"&\" films", type = PlaylistType.XTREAM,
            url = "https://provider.example", username = "user", password = "secret\npass", userAgent = "TV", enabled = false, createdAt = 123)),
        guides = listOf(EpgSourceEntity(id = 2, playlistId = 7, name = "Guide", url = "https://guide.example/epg.xml")),
        favorites = listOf(FavoriteEntity(7, "stream-1", addedAt = 123)),
        history = listOf(WatchHistoryEntity(7, "stream-1", 1234, 5000, 2000)),
        blocks = setOf("channel:7:stream-2", "group:7:Adult"),
        settings = AppSettings(buffer = BufferSize.LARGE, softwareDecoders = true, epgOffsetMinutes = 30,
            displayMode = DisplayMode.ZOOM, previewFollowsFocus = true, audioPassthrough = false, fullScan = true),
    )
    @Test fun roundTripPreservesConfigurationAndCredentials() {
        assertEquals(state, BackupCodec.decode(BackupCodec.encode(state)))
        assertFalse(BackupCodec.encode(state).contains("\"pin\""))
    }
    @Test fun rejectsUnsupportedVersion() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(state.copy(version = 42))) }
    }
    @Test fun rejectsDanglingReferences() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(state.copy(playlists = emptyList()))) }
    }
    @Test fun rejectsUnversionedFile() {
        val text = BackupCodec.encode(state).replace(Regex("\\s*\"version\": 1,"), "")
        assertThrows(kotlinx.serialization.SerializationException::class.java) { BackupCodec.decode(text) }
    }
    @Test fun emptyConfigurationRoundTrips() {
        val empty = BackupState(version = 1, playlists = emptyList(), guides = emptyList(), favorites = emptyList(), history = emptyList(), blocks = emptySet(), settings = AppSettings())
        assertEquals(empty, BackupCodec.decode(BackupCodec.encode(empty)))
    }
}
