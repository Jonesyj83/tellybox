package com.tellybox.tv.data.backup

/**
 * Checks a decoded backup before any of it is applied.
 *
 * A restore replaces the viewer's whole setup, so the file has to be judged in full first. Catching
 * a bad file halfway through is how you end up with someone's new playlists and their old PIN.
 */
object BackupValidation {

    /** Reasons a backup is refused. The screen turns these into a sentence. */
    enum class Problem {
        NO_PLAYLISTS,
        DUPLICATE_PLAYLISTS,
        GUIDE_WITHOUT_PLAYLIST,
        DUPLICATE_ENTRIES,
        TOO_MUCH_DATA,
        INCOMPLETE,
    }

    private const val MAX_PLAYLISTS = 200
    private const val MAX_GUIDES = 500
    private const val MAX_ENTRIES = 100_000
    private const val MAX_BLOCKS = 10_000

    fun check(state: BackupState): Set<Problem> {
        val problems = LinkedHashSet<Problem>()

        if (state.version != 1) problems += Problem.INCOMPLETE
        if (state.playlists.isEmpty()) problems += Problem.NO_PLAYLISTS

        if (state.playlists.size > MAX_PLAYLISTS ||
            state.guides.size > MAX_GUIDES ||
            state.favorites.size > MAX_ENTRIES ||
            state.history.size > MAX_ENTRIES ||
            state.blocks.size > MAX_BLOCKS
        ) problems += Problem.TOO_MUCH_DATA

        if (state.playlists.any { it.id <= 0L || it.name.isBlank() || it.url.isBlank() }) {
            problems += Problem.INCOMPLETE
        }
        val playlistIds = state.playlists.map { it.id }
        if (playlistIds.size != playlistIds.toSet().size) problems += Problem.DUPLICATE_PLAYLISTS

        if (state.guides.any { it.name.isBlank() || it.url.isBlank() }) problems += Problem.INCOMPLETE
        if (state.guides.any { it.playlistId != null && it.playlistId !in playlistIds }) {
            problems += Problem.GUIDE_WITHOUT_PLAYLIST
        }

        val favouriteKeys = state.favorites.map { it.playlistId to it.channelKey }
        if (favouriteKeys.size != favouriteKeys.toSet().size) problems += Problem.DUPLICATE_ENTRIES
        if (state.favorites.any { it.playlistId !in playlistIds || it.channelKey.isBlank() }) {
            problems += Problem.INCOMPLETE
        }

        val historyKeys = state.history.map { it.playlistId to it.channelKey }
        if (historyKeys.size != historyKeys.toSet().size) problems += Problem.DUPLICATE_ENTRIES
        if (state.history.any { it.playlistId !in playlistIds || it.lastWatched < 0L }) {
            problems += Problem.INCOMPLETE
        }

        if (state.blocks.any { it.isBlank() }) problems += Problem.INCOMPLETE

        return problems
    }
}
