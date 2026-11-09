package com.tellybox.tv.data.backup

import com.tellybox.tv.data.db.EpgSourceEntity
import com.tellybox.tv.data.db.FavoriteEntity
import com.tellybox.tv.data.db.PlaylistEntity
import com.tellybox.tv.data.db.PlaylistType
import com.tellybox.tv.data.db.WatchHistoryEntity
import com.tellybox.tv.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A restore replaces the whole setup, so the file has to be judged before any of it is applied.
 * These pin what gets refused, and just as importantly what doesn't.
 */
class BackupValidationTest {

    private fun playlist(id: Long = 1, name: String = "Main", url: String = "http://example.com/list.m3u") =
        PlaylistEntity(id = id, name = name, type = PlaylistType.M3U, url = url)

    private fun state(
        playlists: List<PlaylistEntity> = listOf(playlist()),
        guides: List<EpgSourceEntity> = emptyList(),
        favorites: List<FavoriteEntity> = emptyList(),
        history: List<WatchHistoryEntity> = emptyList(),
        blocks: Set<String> = emptySet(),
        version: Int = 1,
    ) = BackupState(
        version = version,
        playlists = playlists,
        guides = guides,
        favorites = favorites,
        history = history,
        blocks = blocks,
        settings = AppSettings(),
    )

    @Test
    fun `a plain backup passes`() {
        assertTrue(BackupValidation.check(state()).isEmpty())
    }

    @Test
    fun `a backup with no playlists is refused`() {
        assertEquals(setOf(BackupValidation.Problem.NO_PLAYLISTS), BackupValidation.check(state(playlists = emptyList())))
    }

    @Test
    fun `the same playlist twice is refused`() {
        val twice = listOf(playlist(id = 1), playlist(id = 1, name = "Second"))
        assertTrue(BackupValidation.Problem.DUPLICATE_PLAYLISTS in BackupValidation.check(state(playlists = twice)))
    }

    @Test
    fun `a guide for a playlist that is not in the file is refused`() {
        val guides = listOf(EpgSourceEntity(playlistId = 99, name = "Guide", url = "http://example.com/g.xml"))
        assertEquals(
            setOf(BackupValidation.Problem.GUIDE_WITHOUT_PLAYLIST),
            BackupValidation.check(state(guides = guides)),
        )
    }

    @Test
    fun `a guide for every playlist is allowed`() {
        val guides = listOf(EpgSourceEntity(playlistId = null, name = "Guide", url = "http://example.com/g.xml"))
        assertTrue(BackupValidation.check(state(guides = guides)).isEmpty())
    }

    @Test
    fun `repeated favourites are refused`() {
        val favs = listOf(
            FavoriteEntity(playlistId = 1, channelKey = "abc"),
            FavoriteEntity(playlistId = 1, channelKey = "abc"),
        )
        assertTrue(BackupValidation.Problem.DUPLICATE_ENTRIES in BackupValidation.check(state(favorites = favs)))
    }

    @Test
    fun `a favourite for a missing playlist is refused`() {
        val favs = listOf(FavoriteEntity(playlistId = 7, channelKey = "abc"))
        assertTrue(BackupValidation.Problem.INCOMPLETE in BackupValidation.check(state(favorites = favs)))
    }

    @Test
    fun `history going backwards in time is refused`() {
        val history = listOf(WatchHistoryEntity(playlistId = 1, channelKey = "abc", lastWatched = -5))
        assertTrue(BackupValidation.Problem.INCOMPLETE in BackupValidation.check(state(history = history)))
    }

    @Test
    fun `an empty playlist name is refused`() {
        assertTrue(BackupValidation.Problem.INCOMPLETE in BackupValidation.check(state(playlists = listOf(playlist(name = " ")))))
    }

    @Test
    fun `an unknown version is refused`() {
        assertTrue(BackupValidation.Problem.INCOMPLETE in BackupValidation.check(state(version = 7)))
    }

    @Test
    fun `an absurd number of entries is refused`() {
        val many = (1..100_001).map { FavoriteEntity(playlistId = 1, channelKey = "k$it") }
        assertTrue(BackupValidation.Problem.TOO_MUCH_DATA in BackupValidation.check(state(favorites = many)))
    }
}
