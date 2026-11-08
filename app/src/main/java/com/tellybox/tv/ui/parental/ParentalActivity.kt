package com.tellybox.tv.ui.parental

import com.tellybox.tv.R
import com.tellybox.tv.ui.playlists.contentKindLabel
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tellybox.tv.appContainer
import kotlinx.coroutines.launch

class ParentalActivity : com.tellybox.tv.ui.ScaledActivity() {
    private lateinit var list: LinearLayout
    private val repo get() = appContainer.parental
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        setContentView(ScrollView(this).apply { addView(list) })
        requestPin(always = true, cancel = { finish() }) { render() }
    }
    private fun button(label: String, action: () -> Unit) {
        list.addView(Button(this).apply {
            text = label; isAllCaps = false
            setBackgroundResource(com.tellybox.tv.R.drawable.bg_focusable)
            setOnClickListener { action() }
        })
    }
    private fun render() {
        list.removeAllViews()
        button(if (repo.configured) getString(R.string.ui_change_pin) else getString(R.string.ui_set_pin)) {
            requestPin(always = repo.configured) { newPin() }
        }
        if (!repo.configured) return
        list.addView(TextView(this).apply { text = getString(R.string.ui_blocked_channels_and_groups_are_hidden_while_locked_five_incorrect_pin) })
        button(getString(R.string.ui_unlock_blocked_content)) { requestPin(always = true) { render() } }
        button(getString(R.string.ui_lock_now)) { repo.lock(); finish() }
        button(getString(R.string.ui_require_pin_1_s, getString(if (repo.allScreens) R.string.ui_all_screens else R.string.ui_blocked_channels_only))) {
            requestPin(always = true) {
                AlertDialog.Builder(this).setTitle(getString(R.string.ui_require_pin_for))
                    .setItems(arrayOf(getString(R.string.ui_all_screens), getString(R.string.ui_blocked_channels_only))) { _, i -> repo.setPolicy(i == 0); render() }.show()
            }
        }
        button(getString(R.string.ui_unlock_duration_1_s, if (repo.duration == 0) getString(R.string.ui_until_next_app_start) else resources.getQuantityString(R.plurals.minutes, repo.duration, repo.duration))) {
            requestPin(always = true) {
                AlertDialog.Builder(this).setTitle(getString(R.string.ui_unlock_duration))
                    .setItems(arrayOf(resources.getQuantityString(R.plurals.minutes, 10, 10), resources.getQuantityString(R.plurals.minutes, 30, 30), resources.getQuantityString(R.plurals.hours, 1, 1), resources.getQuantityString(R.plurals.hours, 2, 2), getString(R.string.ui_until_next_app_start))) { _, i ->
                        repo.setDuration(listOf(10, 30, 60, 120, 0)[i]); render()
                    }.show()
            }
        }
        button(getString(R.string.ui_block_channels_and_groups)) { requestPin(always = true) { playlists() } }
    }
    private fun newPin() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(hintText: String) = EditText(this).apply {
            hint = hintText; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false; filters = arrayOf(android.text.InputFilter.LengthFilter(8)); box.addView(this)
        }
        val pin = field(getString(R.string.ui_new_pin_4_8_digits))
        val confirm = field(getString(R.string.ui_confirm_pin))
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.ui_set_pin)).setView(box)
            .setPositiveButton(getString(R.string.ui_save), null).setNegativeButton(getString(R.string.cancel), null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val value = pin.text.toString()
                if (!com.tellybox.tv.data.parental.PinSecurity.valid(value) || value != confirm.text.toString()) {
                    confirm.error = getString(R.string.ui_enter_matching_pins_of_4_8_digits)
                } else lifecycleScope.launch {
                    repo.setPin(value); pin.text.clear(); confirm.text.clear(); dialog.dismiss()
                    requestPin(cancel = { finish() }) { render() }
                }
            }
        }
        dialog.show()
    }
    private fun playlists() {
        lifecycleScope.launch {
            val items = appContainer.database.playlists().getAll()
            AlertDialog.Builder(this@ParentalActivity).setTitle(getString(R.string.ui_choose_playlist))
                .setItems(items.map { it.name }.toTypedArray()) { _, i -> groups(items[i].id) }.show()
        }
    }
    private fun groups(playlist: Long) {
        lifecycleScope.launch {
            val items = appContainer.database.groups().getForPlaylist(playlist)
            AlertDialog.Builder(this@ParentalActivity).setTitle(getString(R.string.ui_choose_group))
                .setItems((listOf(getString(R.string.group_all)) + items.map { getString(R.string.named_kind, it.name, contentKindLabel(it.kind)).let { label -> if (repo.blocked(repo.groupKey(it))) getString(R.string.blocked_name, label) else label } }).toTypedArray()) { _, i ->
                    if (i == 0) { requestPin(always = true) { channels(playlist, null) }; return@setItems }
                    val group = items[i - 1]
                    AlertDialog.Builder(this@ParentalActivity).setTitle(group.name)
                        .setItems(arrayOf(getString(R.string.ui_toggle_group_block), getString(R.string.ui_choose_channel))) { _, action ->
                            requestPin(always = true) {
                                if (action == 0) { repo.toggle(repo.groupKey(group)); groups(playlist) }
                                else channels(playlist, group.id)
                            }
                        }.show()
                }.show()
        }
    }
    private fun channels(playlist: Long, group: Long?) {
        lifecycleScope.launch {
            val items = appContainer.database.channels().getForPlaylist(playlist).filter { group == null || it.groupId == group }
            AlertDialog.Builder(this@ParentalActivity).setTitle(getString(R.string.ui_toggle_channel_block))
                .setItems(items.map { if (repo.blocked(repo.channelKey(it))) getString(R.string.blocked_name, it.name) else it.name }.toTypedArray()) { _, i ->
                    requestPin(always = true) { repo.toggle(repo.channelKey(items[i])); channels(playlist, group) }
                }.show()
        }
    }
}
