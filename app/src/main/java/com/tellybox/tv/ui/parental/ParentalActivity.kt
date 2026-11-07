package com.tellybox.tv.ui.parental

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
        button(if (repo.configured) "Change PIN" else "Set PIN") {
            requestPin(always = repo.configured) { newPin() }
        }
        if (!repo.configured) return
        list.addView(TextView(this).apply { text = "Blocked channels and groups are hidden while locked. Five incorrect PINs lock entry for one minute." })
        button("Unlock blocked content") { requestPin(always = true) { render() } }
        button("Lock now") { repo.lock(); finish() }
        button("Require PIN: ${if (repo.allScreens) "All screens" else "Blocked channels only"}") {
            requestPin(always = true) {
                AlertDialog.Builder(this).setTitle("Require PIN for")
                    .setItems(arrayOf("All screens", "Blocked channels only")) { _, i -> repo.setPolicy(i == 0); render() }.show()
            }
        }
        button("Unlock duration: ${if (repo.duration == 0) "Until next app start" else "${repo.duration} minutes"}") {
            requestPin(always = true) {
                AlertDialog.Builder(this).setTitle("Unlock duration")
                    .setItems(arrayOf("10 minutes", "30 minutes", "1 hour", "2 hours", "Until next app start")) { _, i ->
                        repo.setDuration(listOf(10, 30, 60, 120, 0)[i]); render()
                    }.show()
            }
        }
        button("Block channels and groups") { requestPin(always = true) { playlists() } }
    }
    private fun newPin() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun field(hintText: String) = EditText(this).apply {
            hint = hintText; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false; filters = arrayOf(android.text.InputFilter.LengthFilter(8)); box.addView(this)
        }
        val pin = field("New PIN (4–8 digits)")
        val confirm = field("Confirm PIN")
        val dialog = AlertDialog.Builder(this).setTitle("Set PIN").setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val value = pin.text.toString()
                if (!com.tellybox.tv.data.parental.PinSecurity.valid(value) || value != confirm.text.toString()) {
                    confirm.error = "Enter matching PINs of 4–8 digits"
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
            AlertDialog.Builder(this@ParentalActivity).setTitle("Choose playlist")
                .setItems(items.map { it.name }.toTypedArray()) { _, i -> groups(items[i].id) }.show()
        }
    }
    private fun groups(playlist: Long) {
        lifecycleScope.launch {
            val items = appContainer.database.groups().getForPlaylist(playlist)
            AlertDialog.Builder(this@ParentalActivity).setTitle("Choose group")
                .setItems((listOf("All channels") + items.map { "${it.name} (${it.kind})${if (repo.blocked(repo.groupKey(it))) " · Blocked" else ""}" }).toTypedArray()) { _, i ->
                    if (i == 0) { requestPin(always = true) { channels(playlist, null) }; return@setItems }
                    val group = items[i - 1]
                    AlertDialog.Builder(this@ParentalActivity).setTitle(group.name)
                        .setItems(arrayOf("Toggle group block", "Choose channel")) { _, action ->
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
            AlertDialog.Builder(this@ParentalActivity).setTitle("Toggle channel block")
                .setItems(items.map { it.name + if (repo.blocked(repo.channelKey(it))) " · Blocked" else "" }.toTypedArray()) { _, i ->
                    requestPin(always = true) { repo.toggle(repo.channelKey(items[i])); channels(playlist, group) }
                }.show()
        }
    }
}
