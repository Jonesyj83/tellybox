package com.openstream.tv.ui.recordings

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.RecordingEntity
import com.openstream.tv.data.db.RecordingSource
import com.openstream.tv.data.db.RecordingStatus
import com.openstream.tv.databinding.ActivitySimpleListBinding
import com.openstream.tv.schedule.Alarms
import com.openstream.tv.ui.parental.ProtectedActivity
import com.openstream.tv.ui.parental.requestPin
import com.openstream.tv.ui.player.PlayerActivity
import com.openstream.tv.ui.reminders.RemindersActivity
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date

class RecordingsActivity : ProtectedActivity() {

    private enum class Filter { ALL, SCHEDULED, COMPLETED, FAILED }

    private lateinit var binding: ActivitySimpleListBinding
    private val adapter = SimpleRowAdapter { row -> recordings.firstOrNull { it.id == row.id }?.let(::showActions) }
    private var recordings: List<RecordingEntity> = emptyList()
    private var filter = Filter.ALL
    private val filterButtons = HashMap<Filter, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.title.setText(R.string.recordings_title)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.itemAnimator = null

        val labels = mapOf(
            Filter.ALL to R.string.recordings_all,
            Filter.SCHEDULED to R.string.recordings_scheduled,
            Filter.COMPLETED to R.string.recordings_completed,
            Filter.FAILED to R.string.recordings_failed,
        )
        Filter.entries.forEach { f ->
            filterButtons[f] = addAction(getString(labels.getValue(f))) {
                filter = f
                render()
            }
        }
        addAction(getString(R.string.reminders_title)) { startActivity(Intent(this, RemindersActivity::class.java)) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.recordings.observeAll().collect {
                    recordings = it
                    render()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val notes = mutableListOf(getString(R.string.recordings_saved_to, appContainer.recordings.directory().path))
        if (!Alarms.exactAllowed(this)) notes += getString(R.string.recordings_inexact)
        binding.notice.text = notes.joinToString("\n")
        binding.notice.visibility = View.VISIBLE
    }

    private fun addAction(label: String, onClick: () -> Unit): Button {
        val button = Button(this, null, 0, R.style.Button_TV).apply {
            text = label
            setOnClickListener { onClick() }
        }
        binding.actions.addView(button, LinearLayout.LayoutParams(-2, -2).apply { marginStart = 12 })
        return button
    }

    private fun matches(r: RecordingEntity) = when (filter) {
        Filter.ALL -> true
        Filter.SCHEDULED -> r.status == RecordingStatus.SCHEDULED || r.status == RecordingStatus.RECORDING
        Filter.COMPLETED -> r.status == RecordingStatus.COMPLETED
        Filter.FAILED -> r.status == RecordingStatus.FAILED || r.status == RecordingStatus.CANCELLED
    }

    private fun render() {
        filterButtons.forEach { (f, b) -> b.isActivated = f == filter }
        val focusedId = binding.list.focusedChild?.let { binding.list.getChildItemId(it) }
        val rows = recordings.filter(::matches).map { r ->
            SimpleRow(r.id, "${statusLabel(r)}  ·  ${r.title}", detail(r))
        }
        adapter.submit(rows)
        binding.empty.setText(R.string.recordings_empty)
        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (focusedId != null) {
            val pos = rows.indexOfFirst { it.id == focusedId }
            if (pos >= 0) binding.list.post { binding.list.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
        } else if (!binding.actions.hasFocus() && rows.isNotEmpty()) {
            binding.list.post { binding.list.getChildAt(0)?.requestFocus() }
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
