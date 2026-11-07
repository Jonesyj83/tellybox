package com.tellybox.tv.ui.reminders

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
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.db.ReminderEntity
import com.tellybox.tv.ui.chrome.Category
import com.tellybox.tv.ui.chrome.Section
import com.tellybox.tv.ui.chrome.SectionActivity
import com.tellybox.tv.ui.chrome.SimpleRow
import com.tellybox.tv.ui.chrome.SimpleRowAdapter
import com.tellybox.tv.ui.chrome.TwoColumn
import kotlinx.coroutines.launch
import java.util.Date

class RemindersActivity : SectionActivity() {

    private enum class Filter { ALL, TODAY, LATER }

    override val section = Section.REMINDERS
    private lateinit var view: TwoColumn
    private var reminders: List<ReminderEntity> = emptyList()
    private val adapter = SimpleRowAdapter { row -> reminders.firstOrNull { it.id == row.id }?.let(::confirmDelete) }
    private var overlayButton: Button? = null
    private var filter = Filter.ALL
    private var landed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TwoColumn(layoutInflater, chrome.sectionContent) { c ->
            filter = Filter.valueOf(c.key)
            render()
        }
        columns = view
        setSectionContent(view.b.root)
        view.b.title.setText(R.string.reminders_title)
        view.b.list.layoutManager = LinearLayoutManager(this)
        view.b.list.adapter = adapter
        view.categories.activeKey = filter.name

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            overlayButton = Button(this, null, 0, R.style.Button_TV_Small).apply {
                setText(R.string.reminders_allow_popups)
                setOnClickListener { openOverlaySettings() }
            }
            view.b.actions.addView(overlayButton, LinearLayout.LayoutParams(-2, -2))
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

    override fun onDestroy() {
        view.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)
        overlayButton?.visibility = if (blocked) View.VISIBLE else View.GONE
        view.b.notice.setText(if (blocked) R.string.reminders_overlay_notice else R.string.reminders_notice)
        view.b.notice.visibility = View.VISIBLE
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

    private fun endOfToday(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        add(java.util.Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    private fun matches(r: ReminderEntity, f: Filter, midnight: Long) = when (f) {
        Filter.ALL -> true
        Filter.TODAY -> r.programStart < midnight
        Filter.LATER -> r.programStart >= midnight
    }

    private fun render() {
        val midnight = endOfToday()
        val names = mapOf(Filter.ALL to R.string.reminders_all, Filter.TODAY to R.string.reminders_today, Filter.LATER to R.string.reminders_later)
        view.categories.submit(Filter.entries.map { f ->
            Category(f.name, getString(names.getValue(f)), reminders.count { matches(it, f, midnight) })
        })
        val day = DateFormat.getMediumDateFormat(this)
        val time = DateFormat.getTimeFormat(this)
        val rows = reminders.filter { matches(it, filter, midnight) }.map { r ->
            SimpleRow(
                r.id, r.title,
                "${day.format(Date(r.programStart))} ${time.format(Date(r.programStart))}",
                "${r.channelName}  ·  " + getString(R.string.reminder_alert_at, time.format(Date(r.remindAt))),
            )
        }
        adapter.submit(rows)
        view.b.subtitle.text = resources.getQuantityString(R.plurals.reminders_count, reminders.size, reminders.size)
        view.b.empty.setText(R.string.reminders_empty)
        view.b.empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        if (!landed) {
            landed = true
            view.b.list.post { if (rows.isEmpty() || !view.focusList()) view.focusCategories() }
        } else if (view.b.list.hasFocus() && view.b.list.focusedChild == null) {
            view.focusList()
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
