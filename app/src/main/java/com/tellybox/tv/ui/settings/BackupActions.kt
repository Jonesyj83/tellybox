package com.tellybox.tv.ui.settings

import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.tellybox.tv.appContainer
import com.tellybox.tv.ui.parental.requestPin
import com.tellybox.tv.data.backup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun SettingsActivity.exportBackup(uri: Uri) = lifecycleScope.launch {
    val result = runCatching {
        val c = appContainer
        val db = c.database
        val state = db.withTransaction { BackupState(version = 1, playlists = db.playlists().getAll(), guides = db.epg().observeSources().first(),
            favorites = db.favorites().observeAll().first(), history = db.history().getAll(), blocks = c.parental.exportBlocks(), settings = c.settings.get()) }
        withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(BackupCodec.encode(state)) } }
    }
    AlertDialog.Builder(this@exportBackup).setMessage(result.fold({ "Backup exported. Keep this file private: it contains playlist credentials. The parental PIN is excluded." }, { "Export failed: ${it.message}" })).setPositiveButton("OK", null).show()
}

internal fun SettingsActivity.importBackup(uri: Uri) = lifecycleScope.launch {
    val result = runCatching { withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
            val text = StringBuilder().also { out ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(out.length + count <= 16_000_000) { "Backup is too large" }
                    out.append(buffer, 0, count)
                }
            }.toString()
            require(text.length <= 16_000_000) { "Backup is too large" }
            BackupCodec.decode(text)
        }
    } }
    val state = result.getOrElse {
        AlertDialog.Builder(this@importBackup).setMessage("Import failed: ${it.message}").setPositiveButton("OK", null).show()
        return@launch
    }
    AlertDialog.Builder(this@importBackup).setTitle("Replace saved configuration?")
        .setMessage("Replace playlists (${state.playlists.size}), guide assignments, favourites, watch history, blocked channels/groups and settings. Cached channels and programmes will be cleared; refresh playlists after importing. Recordings and reminders remain. The parental PIN is not included and will stay unchanged.")
        .setNegativeButton("Cancel", null).setPositiveButton("Replace") { _, _ -> requestPin(always = true) { lifecycleScope.launch {
            val restored = runCatching {
                val c = appContainer
                val db = c.database
                db.withTransaction {
                    db.playlists().getAll().forEach { db.playlists().delete(it.id) }
                    db.epg().observeSources().first().forEach { db.epg().deleteSource(it.id) }
                    state.playlists.forEach { db.playlists().insert(it) }
                    state.guides.forEach { db.epg().insertSource(it.copy(lastUpdated = 0, lastError = null)) }
                    state.favorites.forEach { db.favorites().insert(it) }
                    db.history().clear()
                    state.history.forEach { db.history().upsert(it) }
                }
                c.settings.replace(state.settings)
                c.parental.replaceBlocks(state.blocks)
            }
            AlertDialog.Builder(this@importBackup).setMessage(restored.fold({ "Backup restored. Open Playlists to refresh streams." }, { "Restore failed: ${it.message}" })).setPositiveButton("OK", null).show()
        } } }.show()
}
