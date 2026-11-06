package com.openstream.tv.ui.reminders

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.db.ReminderEntity
import com.openstream.tv.databinding.ActivitySimpleListBinding
import com.openstream.tv.ui.parental.ProtectedActivity
import com.openstream.tv.ui.recordings.SimpleRow
import com.openstream.tv.ui.recordings.SimpleRowAdapter
import kotlinx.coroutines.launch
import java.util.Date

class RemindersActivity : ProtectedActivity() {

    private lateinit var binding: ActivitySimpleListBinding
    private var reminders: List<ReminderEntity> = emptyList()
    private val adapter = SimpleRowAdapter { row -> reminders.firstOrNull { it.id == row.id }?.let(::confirmDelete) }
    private var overlayButton: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.title.setText(R.string.reminders_title)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.itemAnimator = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            overlayButton = Button(this, null, 0, R.style.Button_TV).apply {
                setText(R.string.reminders_allow_popups)
                setOnClickListener { openOverlaySettings() }
            }
            binding.actions.addView(overlayButton, LinearLayout.LayoutParams(-2, -2))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.reminders.observeAll().collect {
                    reminders = it
                    render()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)
        overlayButton?.visibility = if (blocked) View.VISIBLE else View.GONE
        binding.notice.setText(if (blocked) R.string.reminders_overlay_notice else R.string.reminders_notice)
        binding.notice.visibility = View.VISIBLE
    }

    private fun openOverlaySettings() {
        val specific = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(specific)
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this).setMessage(R.string.reminders_overlay_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun render() {
        val day = DateFormat.getMediumDateFormat(this)
        val time = DateFormat.getTimeFormat(this)
        val rows = reminders.map { r ->
            SimpleRow(
                r.id, r.title,
                "${r.channelName}  ·  ${day.format(Date(r.programStart))} ${time.format(Date(r.programStart))}  ·  " +
                    getString(R.string.reminder_alert_at, time.format(Date(r.remindAt))),
            )
        }
        adapter.submit(rows)
        binding.empty.setText(R.string.reminders_empty)
        binding.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (rows.isNotEmpty() && !binding.list.hasFocus() && !binding.actions.hasFocus()) {
            binding.list.post { binding.list.getChildAt(0)?.requestFocus() }
        }
    }

    private fun confirmDelete(r: ReminderEntity) {
        AlertDialog.Builder(this)
            .setTitle(r.title)
            .setMessage(R.string.reminder_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> lifecycleScope.launch { appContainer.reminders.remove(r.id) } }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
