package com.tellybox.tv.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tellybox.tv.data.settings.SettingsRepository

/** What the background refresh is doing right now, for a status line in the UI. */
sealed interface RefreshStatus {
    data object Idle : RefreshStatus
    data class Playlist(val name: String) : RefreshStatus
    data object Guide : RefreshStatus
}

/**
 * Brings stale playlists and guides up to date, on app start and from [RefreshWorker]. Anything refreshed
 * within the configured guide refresh interval is left alone so restarting the app doesn't hammer providers.
 */
class BackgroundRefresh(
    private val playlists: PlaylistRepository,
    private val epg: EpgRepository,
    private val settings: SettingsRepository,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow<RefreshStatus>(RefreshStatus.Idle)
    val status: StateFlow<RefreshStatus> = _status

    /** Re-imports every guide now, e.g. after the time offset or description setting changed. */
    suspend fun refreshAllGuides() {
        lock.lock()
        try {
            _status.value = RefreshStatus.Guide
            epg.refreshAll()
        } finally {
            _status.value = RefreshStatus.Idle
            lock.unlock()
        }
    }

    suspend fun refreshStale(slackMs: Long = 0) {
        if (!lock.tryLock()) return
        try {
            val cutoff = RefreshSchedule.staleCutoff(System.currentTimeMillis(), settings.get().guideRefreshHours, slackMs)
            playlists.getPlaylists()
                .filter { it.enabled && it.lastUpdated < cutoff }
                .forEach {
                    _status.value = RefreshStatus.Playlist(it.name)
                    playlists.refresh(it.id)
                }
            val staleGuides = epg.staleSources(cutoff)
            if (staleGuides.isNotEmpty()) {
                _status.value = RefreshStatus.Guide
                staleGuides.forEach { epg.refresh(it) }
            }
        } finally {
            _status.value = RefreshStatus.Idle
            lock.unlock()
        }
    }
}

/** Serialises work per key, so two refreshes of the same playlist never interleave. */
internal class KeyedLock<K> {
    private val locks = HashMap<K, Mutex>()

    suspend fun <T> withLock(key: K, block: suspend () -> T): T {
        val mutex = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        return mutex.withLock { block() }
    }
}
