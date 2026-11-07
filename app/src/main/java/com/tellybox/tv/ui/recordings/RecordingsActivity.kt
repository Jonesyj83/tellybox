package com.tellybox.tv.ui.recordings

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.RecordingEntity
import com.tellybox.tv.data.db.RecordingSource
import com.tellybox.tv.data.db.RecordingStatus
import com.tellybox.tv.schedule.Alarms
import com.tellybox.tv.ui.chrome.Category
import com.tellybox.tv.ui.chrome.Section
import com.tellybox.tv.ui.chrome.SectionActivity
import com.tellybox.tv.ui.chrome.SimpleRow
import com.tellybox.tv.ui.chrome.SimpleRowAdapter
import com.tellybox.tv.ui.chrome.TwoColumn
import com.tellybox.tv.ui.parental.requestPin
import com.tellybox.tv.ui.player.PlayerActivity
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

class RecordingsActivity : SectionActivity() {

    private enum class Filter { ALL, SCHEDULED, COMPLETED, FAILED }

    override val section = Section.RECORDINGS
    private lateinit var view: TwoColumn
    private val adapter = SimpleRowAdapter { row -> recordings.firstOrNull { it.id == row.id }?.let(::showActions) }
    private var recordings: List<RecordingEntity> = emptyList()
    private var filter = Filter.ALL
    private var landed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = savedInstanceState?.getString(STATE_FILTER)?.let { name -> Filter.entries.firstOrNull { it.name == name } } ?: Filter.ALL
        view = TwoColumn(layoutInflater, chrome.sectionContent) { c ->
            filter = Filter.valueOf(c.key)
            render()
        }
        columns = view
        setSectionContent(view.b.root)
        view.b.title.setText(R.string.recordings_title)
        view.b.list.layoutManager = LinearLayoutManager(this)
        view.b.list.adapter = adapter
        view.categories.activeKey = filter.name

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.recordings.observeAll().collect {
                    recordings = it
                    render()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FILTER, filter.name)
    }

    override fun onDestroy() {
        view.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        view.b.subtitle.text = getString(R.string.recordings_saved_to, appContainer.recordings.directory().path)
        view.b.notice.setText(R.string.recordings_inexact)
        view.b.notice.visibility = if (Alarms.exactAllowed(this)) View.GONE else View.VISIBLE
    }

    private fun matches(r: RecordingEntity, f: Filter) = when (f) {
        Filter.ALL -> true
        Filter.SCHEDULED -> r.status == RecordingStatus.SCHEDULED || r.status == RecordingStatus.RECORDING
        Filter.COMPLETED -> r.status == RecordingStatus.COMPLETED
        Filter.FAILED -> r.status == RecordingStatus.FAILED || r.status == RecordingStatus.CANCELLED
    }

    private fun render() {
        val labels = mapOf(
            Filter.ALL to R.string.recordings_all,
            Filter.SCHEDULED to R.string.recordings_scheduled,
            Filter.COMPLETED to R.string.recordings_completed,
            Filter.FAILED to R.string.recordings_failed,
        )
        view.categories.submit(Filter.entries.map { f ->
            Category(f.name, getString(labels.getValue(f)), recordings.count { matches(it, f) })
        })
        val focusedId = view.b.list.focusedChild?.let { view.b.list.getChildItemId(it) }
        val rows = recordings.filter { matches(it, filter) }.map { r ->
            SimpleRow(r.id, r.title, statusLabel(r), detail(r), highlight = r.status == RecordingStatus.RECORDING)
        }
        adapter.submit(rows)
        view.b.empty.setText(R.string.recordings_empty)
        view.b.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (focusedId != null) {
            val pos = rows.indexOfFirst { it.id == focusedId }
            if (pos >= 0) view.b.list.post { view.b.list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
            else view.focusList()
        } else if (!landed) {
            landed = true
            view.b.list.post { if (rows.isEmpty() || !view.focusList()) view.focusCategories() }
        }
    }

    private fun statusLabel(r: RecordingEntity) = getString(
        when (r.status) {
            RecordingStatus.SCHEDULED -> R.string.recording_status_scheduled
            RecordingStatus.RECORDING -> R.string.recording_status_recording
            RecordingStatus.COMPLETED -> R.string.recording_status_completed
            RecordingStatus.FAILED -> R.string.recording_status_failed
            RecordingStatus.CANCELLED -> R.string.recording_status_cancelled
        },
    )

    private fun detail(r: RecordingEntity): String {
        val day = DateFormat.getMediumDateFormat(this)
        val time = DateFormat.getTimeFormat(this)
        val parts = mutableListOf(
            r.channelName,
            "${day.format(Date(r.startAt))} ${time.format(Date(r.startAt))}–${time.format(Date(r.stopAt))}",
        )
        if (r.source == RecordingSource.CATCHUP) parts += getString(R.string.recording_from_archive)
        if (r.bytes > 0) parts += Formatter.formatShortFileSize(this, r.bytes)
        r.error?.let { parts += it }
        return parts.joinToString("  ·  ")
    }

    private fun showActions(r: RecordingEntity) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        val repo = appContainer.recordings
        val hasFile = r.filePath?.let { File(it).length() > 0 } == true
        if (hasFile) actions += getString(R.string.recording_play) to { play(r) }
        when (r.status) {
            RecordingStatus.RECORDING -> actions += getString(R.string.recording_stop) to {
                lifecycleScope.launch { repo.stop(r.id) }
            }
            RecordingStatus.SCHEDULED -> actions += getString(R.string.recording_cancel) to {
                lifecycleScope.launch { repo.stop(r.id) }
            }
            else -> Unit
        }
        r.error?.let { reason ->
            actions += getString(R.string.recording_details) to {
                AlertDialog.Builder(this).setTitle(r.title).setMessage(reason).setPositiveButton(android.R.string.ok, null).show()
            }
        }
        actions += getString(R.string.delete) to { confirmDelete(r, hasFile) }
        AlertDialog.Builder(this)
            .setTitle(r.title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun confirmDelete(r: RecordingEntity, hasFile: Boolean) {
        val delete = { lifecycleScope.launch { appContainer.recordings.delete(r.id) }; Unit }
        if (!hasFile) return delete()
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.recording_delete_confirm, r.title))
            .setPositiveButton(R.string.delete) { _, _ -> delete() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val STATE_FILTER = "filter"
    }

    private fun play(r: RecordingEntity) {
        val path = r.filePath ?: return
        if (!File(path).exists()) {
            Toast.makeText(this, R.string.recording_file_missing, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val c = appContainer
            val channel = c.database.channels().findByKey(r.playlistId, r.channelKey)
            val open = { startActivity(PlayerActivity.fileIntent(this@RecordingsActivity, path, r.title)) }
            if (channel != null && !c.parental.allowed(channel, c.database)) requestPin { open() } else open()
        }
    }
}
