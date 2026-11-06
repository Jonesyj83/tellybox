package com.openstream.tv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openstream.tv.R
import com.openstream.tv.appContainer
import com.openstream.tv.data.settings.AppSettings
import com.openstream.tv.data.settings.DisplayMode
import com.openstream.tv.databinding.ActivitySettingsBinding
import com.openstream.tv.databinding.ItemChoiceBinding
import com.openstream.tv.player.BufferSize
import kotlinx.coroutines.launch
import kotlin.math.abs

class SettingsActivity : com.openstream.tv.ui.parental.ProtectedActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val rows = ArrayList<ItemChoiceBinding>()

    private class Row(val title: String, val value: String, val isHeader: Boolean = false, val onClick: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.settings.settings.collect { render(it) }
            }
        }
    }

    private fun header(text: Int) = Row(getString(text), "", isHeader = true) {}

    private fun buildRows(s: AppSettings): List<Row> {
        val repo = appContainer.settings
        fun <T> pick(title: Int, options: List<Pair<String, T>>, current: T, save: suspend (T) -> Unit, after: () -> Unit = {}) {
            val index = options.indexOfFirst { it.second == current }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.first }.toTypedArray(), index) { d, which ->
                    d.dismiss()
                    if (which != index) {
                        lifecycleScope.launch {
                            save(options[which].second)
                            after()
                        }
                    }
                }
                .create()
                .apply {
                    // Open on the current value rather than the top of a long list.
                    setOnShowListener { listView?.let { lv -> lv.setSelection(index); lv.requestFocusFromTouch() } }
                }
                .show()
        }
        val onOff = listOf(getString(R.string.on) to true, getString(R.string.off) to false)
        fun onOff(v: Boolean) = getString(if (v) R.string.on else R.string.off)

        val buffers = BufferSize.entries.map { bufferLabel(it) to it }
        val modes = DisplayMode.entries.map { displayModeLabel(it) to it }
        val intervals = listOf(6, 12, 24, 48).map { resources.getQuantityString(R.plurals.hours, it, it) to it }
        val offsets = (-12 * 60..12 * 60 step 30).map { formatOffset(it) to it }
        val reimportGuides = {
            val c = appContainer
            c.appScope.launch { c.backgroundRefresh.refreshAllGuides() }
            Unit
        }

        return listOf(
            Row("Parental controls", "PIN, blocks and unlock duration") {
                startActivity(android.content.Intent(this, com.openstream.tv.ui.parental.ParentalActivity::class.java))
            },
            Row("Channel name cleanup", s.channelNameTokens.ifEmpty { "Off" }) {
                val input = android.widget.EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3
                    hint = "One prefix or suffix per line (UK: , | HD, FHD)"
                    setText(s.channelNameTokens)
                }
                AlertDialog.Builder(this).setTitle("Strip prefixes and suffixes")
                    .setMessage("One literal token per line. Matching is case-sensitive. Clear to disable.")
                    .setView(input).setPositiveButton("Save") { _, _ ->
                        lifecycleScope.launch { repo.setChannelNameTokens(input.text.toString()) }
                    }.setNegativeButton(R.string.cancel, null).show()
            },
            header(R.string.settings_player),
            Row(getString(R.string.setting_buffer), bufferLabel(s.buffer)) {
                pick(R.string.setting_buffer, buffers, s.buffer, repo::setBuffer)
            },
            Row(getString(R.string.setting_decoders), getString(if (s.softwareDecoders) R.string.decoder_software else R.string.decoder_hardware)) {
                pick(
                    R.string.setting_decoders,
                    listOf(getString(R.string.decoder_hardware) to false, getString(R.string.decoder_software) to true),
                    s.softwareDecoders, repo::setSoftwareDecoders,
                )
            },
            Row(getString(R.string.setting_tunneling), onOff(s.tunneling)) {
                pick(R.string.setting_tunneling, onOff, s.tunneling, repo::setTunneling)
            },
            Row(getString(R.string.setting_display_mode), displayModeLabel(s.displayMode)) {
                pick(R.string.setting_display_mode, modes, s.displayMode, repo::setDisplayMode)
            },
            header(R.string.settings_guide),
            Row(getString(R.string.setting_guide_interval), resources.getQuantityString(R.plurals.hours, s.guideRefreshHours, s.guideRefreshHours)) {
                pick(R.string.setting_guide_interval, intervals, s.guideRefreshHours, repo::setGuideRefreshHours)
            },
            Row(getString(R.string.setting_epg_offset), formatOffset(s.epgOffsetMinutes)) {
                pick(R.string.setting_epg_offset, offsets, s.epgOffsetMinutes, repo::setEpgOffsetMinutes, reimportGuides)
            },
            Row(getString(R.string.setting_store_descriptions), onOff(s.storeDescriptions)) {
                pick(R.string.setting_store_descriptions, onOff, s.storeDescriptions, repo::setStoreDescriptions, reimportGuides)
            },
            Row(getString(R.string.setting_update_guides), "") { reimportGuides() },
        )
    }

    private fun render(s: AppSettings) {
        val list = buildRows(s)
        val focused = rows.indexOfFirst { it.root.hasFocus() }
        binding.list.removeAllViews()
        rows.clear()
        list.forEach { row ->
            val b = ItemChoiceBinding.inflate(layoutInflater, binding.list, false)
            b.choiceTitle.text = row.title
            b.choiceDesc.text = row.value
            b.choiceDesc.visibility = if (row.value.isEmpty()) View.GONE else View.VISIBLE
            b.root.setOnClickListener { row.onClick() }
            binding.list.addView(b.root)
            rows += b
        }
        // Section headers aren't selectable.
        list.forEachIndexed { i, row ->
            if (row.isHeader) {
                rows[i].root.isFocusable = false
                rows[i].root.isClickable = false
                rows[i].root.background = null
                rows[i].choiceTitle.setTextColor(getColor(R.color.accent))
            }
        }
        val target = rows.getOrNull(focused) ?: rows.firstOrNull { it.root.isFocusable }
        target?.root?.requestFocus()
    }

    private fun bufferLabel(b: BufferSize) = getString(
        when (b) {
            BufferSize.SMALL -> R.string.buffer_small
            BufferSize.MEDIUM -> R.string.buffer_medium
            BufferSize.LARGE -> R.string.buffer_large
            BufferSize.VERY_LARGE -> R.string.buffer_very_large
        },
    )

    private fun displayModeLabel(m: DisplayMode) = getString(
        when (m) {
            DisplayMode.FIT -> R.string.display_fit
            DisplayMode.FILL -> R.string.display_fill
            DisplayMode.ZOOM -> R.string.display_zoom
            DisplayMode.REDUCED -> R.string.display_reduced
        },
    )

    private fun formatOffset(minutes: Int): String {
        if (minutes == 0) return getString(R.string.offset_none)
        val sign = if (minutes < 0) "−" else "+"
        return "%s%d:%02d".format(sign, abs(minutes) / 60, abs(minutes) % 60)
    }
}
