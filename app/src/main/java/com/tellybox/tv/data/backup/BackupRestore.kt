package com.tellybox.tv.data.backup

import androidx.room.withTransaction
import com.tellybox.tv.AppContainer
import kotlinx.coroutines.flow.first

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
                version = 1,
                playlists = db.playlists().getAll(),
                guides = db.epg().observeSources().first(),
                favorites = db.favorites().observeAll().first(),
                history = db.history().getAll(),
                blocks = container.parental.exportBlocks(),
                settings = container.settings.get(),
            )
        }
    }

    private suspend fun apply(container: AppContainer, state: BackupState) {
        val db = container.database
        db.withTransaction {
            db.playlists().getAll().forEach { db.playlists().delete(it.id) }
            db.epg().observeSources().first().forEach { db.epg().deleteSource(it.id) }
            state.playlists.forEach { db.playlists().insert(it) }
            state.guides.forEach { db.epg().insertSource(it.copy(lastUpdated = 0, lastError = null)) }
            state.favorites.forEach { db.favorites().insert(it) }
            db.history().clear()
            state.history.forEach { db.history().upsert(it) }
        }
        // These two can still fail; if they do, the caller puts the snapshot back.
        container.settings.replace(state.settings)
        container.parental.replaceBlocks(state.blocks)
    }
}
