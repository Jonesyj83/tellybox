package com.tellybox.tv.ui.settings

import com.tellybox.tv.R
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
    AlertDialog.Builder(this@exportBackup).setMessage(result.fold({ getString(R.string.ui_backup_exported_keep_this_file_private_it_contains_playlist_credential) }, { getString(R.string.ui_export_failed_1_s, it.message.orEmpty()) })).setPositiveButton(getString(R.string.ok), null).show()
}

internal fun SettingsActivity.importBackup(uri: Uri) = lifecycleScope.launch {
    val result = runCatching { withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)!!.bufferedReader().use { reader ->
            val text = StringBuilder().also { out ->
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(out.length + count <= 16_000_000) { getString(R.string.ui_backup_is_too_large) }
                    out.append(buffer, 0, count)
                }
            }.toString()
            require(text.length <= 16_000_000) { getString(R.string.ui_backup_is_too_large) }
            BackupCodec.decode(text)
        }
    } }
    val state = result.getOrElse {
        AlertDialog.Builder(this@importBackup).setMessage(getString(R.string.ui_import_failed_1_s, it.message.orEmpty())).setPositiveButton(getString(R.string.ok), null).show()
        return@launch
    }
    // Judge the whole file before touching anything: a restore replaces the entire setup.
    val problems = BackupValidation.check(state)
    if (problems.isNotEmpty()) {
        val reasons = problems.map { getString(it.reason()) }.joinToString("\n")
        AlertDialog.Builder(this@importBackup)
            .setTitle(getString(R.string.backup_rejected_title))
            .setMessage(reasons)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
        return@launch
    }
    AlertDialog.Builder(this@importBackup).setTitle(getString(R.string.ui_replace_saved_configuration))
        .setMessage(getString(R.string.ui_replace_playlists_1_d_guide_assignments_favourites_watch_history_block, state.playlists.size))
        .setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.ui_replace)) { _, _ -> requestPin(always = true) { lifecycleScope.launch {
            val restored = BackupRestore.restore(appContainer, state)
            AlertDialog.Builder(this@importBackup).setMessage(restored.fold({ getString(R.string.ui_backup_restored_open_playlists_to_refresh_streams) }, { getString(R.string.ui_restore_failed_1_s, it.message.orEmpty()) })).setPositiveButton(getString(R.string.ok), null).show()
        } } }.show()
}

/** The sentence shown for each reason a backup was refused. */
private fun BackupValidation.Problem.reason(): Int = when (this) {
    BackupValidation.Problem.NO_PLAYLISTS -> R.string.backup_problem_no_playlists
    BackupValidation.Problem.DUPLICATE_PLAYLISTS -> R.string.backup_problem_duplicate_playlists
    BackupValidation.Problem.GUIDE_WITHOUT_PLAYLIST -> R.string.backup_problem_guide_without_playlist
    BackupValidation.Problem.DUPLICATE_ENTRIES -> R.string.backup_problem_duplicate_entries
    BackupValidation.Problem.TOO_MUCH_DATA -> R.string.backup_problem_too_much_data
    BackupValidation.Problem.INCOMPLETE -> R.string.backup_problem_incomplete
}
