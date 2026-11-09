package com.tellybox.tv.ui.settings

import android.net.Uri
import com.tellybox.tv.R
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.tellybox.tv.appContainer
import com.tellybox.tv.ui.parental.requestPin
import com.tellybox.tv.data.backup.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Writes a backup and says where it went.
 *
 * There is no save dialog: the system one cannot be driven with a remote, so the file is named
 * after the moment it was taken and left in Downloads, where a phone or laptop can find it.
 */
internal fun SettingsActivity.exportBackup() = lifecycleScope.launch {
    val name = BackupFiles.defaultName()
    val result = runCatching {
        val c = appContainer
        val db = c.database
        val state = db.withTransaction { BackupState(version = 2, playlists = db.playlists().getAll(), guides = db.epg().all(),
            favorites = db.favorites().all(), history = db.history().getAll(), blocks = c.parental.exportBlocks(), parental = c.parental.exportConfiguration(), settings = c.settings.get()) }
        withContext(Dispatchers.IO) { BackupFiles.save(this@exportBackup, name, BackupCodec.encode(state)) }
    }
    AlertDialog.Builder(this@exportBackup)
        .setMessage(result.fold(
            { getString(R.string.backup_saved, name) },
            { getString(R.string.backup_export_failed, it.message.orEmpty()) },
        ))
        .setPositiveButton(getString(R.string.ok), null)
        .show()
}

/**
 * Lists the backups already in Downloads so importing is a single press, with the system picker
 * kept as a last resort for a file kept somewhere else — a USB stick, for instance.
 */
internal fun SettingsActivity.chooseBackup(onChooseFile: () -> Unit) = lifecycleScope.launch {
    val files = withContext(Dispatchers.IO) { runCatching { BackupFiles.list(this@chooseBackup) }.getOrDefault(emptyList()) }
    if (files.isEmpty()) {
        // A backup written before a reinstall is still on the card, but the reinstall no longer
        // owns it and the index no longer lists it — the picker is the only way back to it.
        AlertDialog.Builder(this@chooseBackup)
            .setTitle(getString(R.string.backup_import))
            .setMessage(getString(R.string.backup_none_found))
            .setPositiveButton(getString(R.string.common_choose_file)) { _, _ -> onChooseFile() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
        return@launch
    }
    val options = files + getString(R.string.common_choose_file)
    AlertDialog.Builder(this@chooseBackup)
        .setTitle(getString(R.string.backup_import))
        .setItems(options.toTypedArray()) { _, which ->
            if (which < files.size) importBackup(files[which]) else onChooseFile()
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
}

internal fun SettingsActivity.importBackup(name: String) = lifecycleScope.launch {
    val result = runCatching { withContext(Dispatchers.IO) { BackupFiles.read(this@importBackup, name) } }
    result.onFailure { failure -> showImportFailure(failure) }
    result.onSuccess { text -> applyBackupText(text) }
}

internal fun SettingsActivity.importBackupUri(uri: Uri) = lifecycleScope.launch {
    val result = runCatching { withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw java.io.FileNotFoundException(uri.toString())
    } }
    result.onFailure { failure -> showImportFailure(failure) }
    result.onSuccess { text -> applyBackupText(text) }
}

private fun SettingsActivity.showImportFailure(failure: Throwable) {
    AlertDialog.Builder(this).setMessage(getString(R.string.backup_import_failed, failure.message.orEmpty()))
        .setPositiveButton(getString(R.string.ok), null).show()
}

/** Decode, judge the whole file, then ask before replacing anything. */
private fun SettingsActivity.applyBackupText(text: String) = lifecycleScope.launch {
    val decoded = runCatching {
        require(text.length <= 16_000_000) { getString(R.string.backup_too_large) }
        BackupCodec.decode(text)
    }
    val state = decoded.getOrElse {
        showImportFailure(it)
        return@launch
    }
    // Judge the file before touching anything: a restore replaces the entire setup.
    val problems = BackupValidation.check(state)
    if (problems.isNotEmpty()) {
        val reasons = problems.map { getString(it.reason()) }.joinToString("\n")
        AlertDialog.Builder(this@applyBackupText)
            .setTitle(getString(R.string.backup_rejected_title))
            .setMessage(reasons)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
        return@launch
    }
    AlertDialog.Builder(this@applyBackupText).setTitle(getString(R.string.backup_replace_title))
        .setMessage(getString(R.string.backup_replace_summary, state.playlists.size) + if (state.parental == null || state.version == 1) "\n" + getString(R.string.backup_pin_not_included) else "")
        .setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.common_replace)) { _, _ -> requestPin(always = true) { lifecycleScope.launch {
            val restored = BackupRestore.restore(appContainer, state)
            AlertDialog.Builder(this@applyBackupText).setMessage(restored.fold({ getString(R.string.backup_restored) }, { getString(R.string.backup_restore_failed, it.message.orEmpty()) })).setPositiveButton(getString(R.string.ok), null).show()
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
