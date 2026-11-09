package com.tellybox.tv.data.backup

import androidx.room.withTransaction
import kotlinx.coroutines.launch
import com.tellybox.tv.AppContainer

/**
 * Applies a backup in full, or puts back what was there before.
 *
 * The database, the settings and the parental blocks live in three different stores, so a restore
 * that writes them in turn can fail in the middle and leave a mix of old and new — a new playlist
 * list with the previous PIN, for example. This takes a snapshot first and puts it back if any
 * step fails, so the viewer either gets the backup or keeps what they had.
 */
object BackupRestore {

    /** Thrown when the restore failed and the previous state could not be put back either. */
    class RollbackFailed(cause: Throwable, rollback: Throwable) :
        IllegalStateException("Restore failed and could not be undone", cause) {
        init {
            addSuppressed(rollback)
        }
    }

    suspend fun restore(container: AppContainer, state: BackupState): Result<Unit> {
        val snapshot = capture(container)
        return try {
            apply(container, state)
            Result.success(Unit)
        } catch (failure: Throwable) {
            val rollback = runCatching { apply(container, snapshot) }
            rollback.exceptionOrNull()?.let { Result.failure(RollbackFailed(failure, it)) }
                ?: Result.failure(failure)
        }
    }

    private suspend fun capture(container: AppContainer): BackupState {
        val db = container.database
        return db.withTransaction {
            BackupState(
                version = 2,
                playlists = db.playlists().getAll(),
                guides = db.epg().all(),
                favorites = db.favorites().all(),
                history = db.history().getAll(),
                blocks = container.parental.exportBlocks(),
                parental = container.parental.exportConfiguration(),
                settings = container.settings.get(),
            )
        }
    }

    private suspend fun apply(container: AppContainer, state: BackupState) {
        val db = container.database
        // Anything still running against the rows we are about to replace has to stop first,
        // and the cached portal connection goes with it.
        db.playlists().getAll().forEach { container.playlists.forgetRuntimeState(it.id) }
        db.withTransaction {
            db.playlists().getAll().forEach { db.playlists().delete(it.id) }
            db.epg().all().forEach { db.epg().deleteSource(it.id) }
            // Marked never refreshed, so the lists refill themselves instead of coming back
            // empty for a whole refresh interval.
            state.playlists.forEach { db.playlists().insert(it.copy(lastUpdated = 0, lastError = null)) }
            state.guides.forEach { db.epg().insertSource(it.copy(lastUpdated = 0, lastError = null)) }
            state.favorites.forEach { db.favorites().insert(it) }
            db.history().clear()
            state.history.forEach { db.history().upsert(it) }
        }
        // These two can still fail; if they do, the caller puts the snapshot back.
        container.settings.replace(state.settings)
        com.tellybox.tv.data.parental.applyParentalBackup(state.blocks, state.parental.takeIf { state.version >= 2 }) { blocks, config ->
            container.parental.replaceConfiguration(blocks, config)
        }
        // Fill the restored lists back in without making the caller wait for it.
        container.appScope.launch { container.backgroundRefresh.refreshStale() }
    }
}
