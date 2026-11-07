package com.opentelly.tv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.opentelly.tv.R
import com.opentelly.tv.appContainer
import com.opentelly.tv.data.settings.AppSettings
import com.opentelly.tv.data.settings.DisplayMode
import com.opentelly.tv.ui.chrome.Category
import com.opentelly.tv.ui.chrome.Section
import com.opentelly.tv.ui.chrome.SectionActivity
import com.opentelly.tv.ui.chrome.SimpleRow
import com.opentelly.tv.ui.chrome.SimpleRowAdapter
import com.opentelly.tv.ui.chrome.TwoColumn
import com.opentelly.tv.player.BufferSize
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class SettingsActivity : SectionActivity() {

    override val section = Section.SETTINGS
    private lateinit var view: TwoColumn
    private var rowActions: List<() -> Unit> = emptyList()
    private val adapter = SimpleRowAdapter { row -> rowActions.getOrNull(row.id.toInt())?.invoke() }

    private var matchSummary = "Guide matches 0 of 0 channels"
    private var guideIdSummary = ""
    private var current: String = SECTIONS.first()
    private var currentSettings: AppSettings? = null
    private val exportFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { it?.let(::exportBackup) }
    private val importFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(::importBackup) }

    private class Row(val title: String, val value: String, val isHeader: Boolean = false, val description: String = "", val onClick: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = savedInstanceState?.getString("section")?.takeIf { it in SECTIONS } ?: SECTIONS.first()
        view = TwoColumn(layoutInflater, chrome.sectionContent) { c ->
            current = c.key
            view.b.list.scrollToPosition(0)
            currentSettings?.let(::render)
        }
        columns = view
        setSectionContent(view.b.root)
        view.b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        view.b.list.adapter = adapter
        view.categories.submit(SECTIONS.map { Category(it, it) })
        view.categories.activeKey = current
        view.b.list.post { view.focusCategories() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // How many channels even carry a tvg-id: without one, no guide can attach.
                appContainer.database.epg().let { epg ->
                    val ids = epg.knownTvgIds(null).size
                    val guideChannels = epg.guideChannelCount()
                    val programmes = epg.guideProgramCount()
                    guideIdSummary = "$ids channel ids here · guide has $guideChannels channels, $programmes programmes"
                }
                kotlinx.coroutines.flow.combine(appContainer.database.epg().observeMatchCount(), appContainer.database.epg().observeLiveCount()) { _, _ -> }
                    .collect { currentSettings?.let(::render) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.settings.settings.collect { render(it) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("section", current)
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
        val padOptions = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30).map { minutes(it) to it }
        val reimportGuides = {
            val c = appContainer
            c.appScope.launch { c.backgroundRefresh.refreshAllGuides() }
            Unit
        }

        return listOf(
            Row("Parental controls", "PIN, blocks and unlock duration") {
                startActivity(android.content.Intent(this, com.opentelly.tv.ui.parental.ParentalActivity::class.java))
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
            Row("Show clock", onOff(s.showClock)) { lifecycleScope.launch { repo.setShowClock(!s.showClock) } },
            Row("Dim past programmes", onOff(s.dimPast)) { lifecycleScope.launch { repo.setDimPast(!s.dimPast) } },
            Row("Panel transparency", "${100 - s.panelOpacity}%") {
                AlertDialog.Builder(this).setTitle("Panel transparency").setItems(arrayOf("Opaque", "10%", "25%", "40%")) { _, i -> lifecycleScope.launch { repo.setPanelOpacity(listOf(100, 90, 75, 60)[i]) } }.show()
            },
            Row("Full guide scan", onOff(s.fullScan)) { lifecycleScope.launch { repo.setFullScan(!s.fullScan) } },
            header(R.string.settings_player),
            Row("Audio decoder", if (s.softwareAudioDecoders) "Software preferred" else "Hardware preferred") {
                AlertDialog.Builder(this).setTitle("Audio decoder").setSingleChoiceItems(arrayOf("Hardware preferred", "Software preferred"), if (s.softwareAudioDecoders) 1 else 0) { dialog, index ->
                    dialog.dismiss()
                    lifecycleScope.launch { repo.setSoftwareAudioDecoders(index == 1) }
                }.show()
            },
            Row("Audio passthrough", onOff(s.audioPassthrough)) { lifecycleScope.launch { repo.setAudioPassthrough(!s.audioPassthrough) } },
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
            Row(getString(R.string.setting_background_playback), onOff(s.backgroundPlayback)) {
                pick(R.string.setting_background_playback, onOff, s.backgroundPlayback, repo::setBackgroundPlayback)
            },
            Row(getString(R.string.setting_preview_follows_focus), onOff(s.previewFollowsFocus)) {
                pick(R.string.setting_preview_follows_focus, onOff, s.previewFollowsFocus, repo::setPreviewFollowsFocus)
            },
            Row(getString(R.string.setting_multiview_warning), onOff(s.multiviewConnectionWarning)) {
                pick(R.string.setting_multiview_warning, onOff, s.multiviewConnectionWarning, repo::setMultiviewConnectionWarning)
            },
            Row(getString(R.string.setting_resume_last_channel), onOff(s.resumeLastChannel)) {
                pick(R.string.setting_resume_last_channel, onOff, s.resumeLastChannel, repo::setResumeLastChannel)
            },
            header(R.string.settings_recording),
            Row(getString(R.string.recordings_title), "") { openSection(Section.RECORDINGS) },
            Row(getString(R.string.reminders_title), "") { openSection(Section.REMINDERS) },
            Row(getString(R.string.setting_pad_before), minutes(s.recordPadBefore)) {
                pick(R.string.setting_pad_before, padOptions, s.recordPadBefore, repo::setRecordPadBefore)
            },
            Row(getString(R.string.setting_pad_after), minutes(s.recordPadAfter)) {
                pick(R.string.setting_pad_after, padOptions, s.recordPadAfter, repo::setRecordPadAfter)
            },
            Row(getString(R.string.setting_instant_length), minutes(s.instantRecordMinutes)) {
                pick(R.string.setting_instant_length, listOf(15, 30, 60, 90, 120, 180, 240).map { minutes(it) to it },
                    s.instantRecordMinutes, repo::setInstantRecordMinutes)
            },
            Row(getString(R.string.setting_reminder_before), minutes(s.reminderMinutes)) {
                pick(R.string.setting_reminder_before, listOf(0, 1, 2, 5, 10, 15, 30).map { minutes(it) to it },
                    s.reminderMinutes, repo::setReminderMinutes)
            },
            Row(getString(R.string.setting_reminder_popup), seconds(s.reminderPopupSeconds)) {
                pick(R.string.setting_reminder_popup, listOf(10, 20, 30, 60, 120).map { seconds(it) to it },
                    s.reminderPopupSeconds, repo::setReminderPopupSeconds)
            },
            header(R.string.settings_guide),
            Row(guideIdSummary, "Refresh") { reimportGuides() },
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

    private fun maintenance() = listOf(Row("Clear EPG", "Maintenance") { clearEpg() }, Row("Clear logo cache", "Maintenance") {
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                coil.Coil.imageLoader(this@SettingsActivity).diskCache?.clear()
                coil.Coil.imageLoader(this@SettingsActivity).memoryCache?.clear()
            }
            android.widget.Toast.makeText(this@SettingsActivity, "Logo cache cleared", android.widget.Toast.LENGTH_SHORT).show()
        }
    })

    private fun clearEpg() = lifecycleScope.launch {
        val dao = appContainer.database.epg()
        val sources = appContainer.epg.observeSources().let { it.first() }
        val count = sources.sumOf { dao.programCount(it.id) }
        AlertDialog.Builder(this@SettingsActivity).setTitle("Clear EPG?").setMessage("Delete $count programmes? Guide sources stay assigned.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> lifecycleScope.launch {
                sources.forEach { dao.deleteProgramsForSource(it.id); dao.deleteChannelsForSource(it.id); dao.setSourceRefreshResult(it.id, 0, null) }
                android.widget.Toast.makeText(this@SettingsActivity, "EPG cleared", android.widget.Toast.LENGTH_SHORT).show()
            } }.show()
    }

    private fun showGuides(focusIndex: Int = 0): kotlinx.coroutines.Job = lifecycleScope.launch {
        val sources = appContainer.database.epg().observeSources().first().filter { it.enabled }
        val playlists = appContainer.database.playlists().getAll()
        val labels = sources.map { source -> "${source.name}: ${source.url}\n${if (source.lastUpdated == 0L) "Never updated" else java.util.Date(source.lastUpdated).toString()}${source.lastError?.let { " — $it" }.orEmpty()}" } + "Assign guide to playlist"
        AlertDialog.Builder(this@SettingsActivity).setTitle("Guide sources").setItems(labels.toTypedArray()) { _, index ->
            if (index < sources.size) {
                val source = sources[index]
                AlertDialog.Builder(this@SettingsActivity).setTitle(source.name).setItems(arrayOf("Refresh now", "Remove guide")) { _, action -> lifecycleScope.launch {
                    if (action == 0) {
                        val result = appContainer.epg.refresh(source)
                        AlertDialog.Builder(this@SettingsActivity).setMessage(result.fold({ it.error ?: "Guide refreshed" }, { "Refresh failed: ${it.message}" })).setPositiveButton("OK", null).show()
                    } else appContainer.epg.deleteSource(source.id)
                } }.setOnCancelListener { showGuides(index) }.show()
            } else chooseGuidePlaylist(playlists)
        }.setPositiveButton("Close", null).create().apply {
            setOnShowListener { listView.setSelection(focusIndex.coerceAtMost(listView.count - 1)); listView.requestFocus() }
        }.show()
    }

    private fun chooseGuidePlaylist(playlists: List<com.opentelly.tv.data.db.PlaylistEntity>, focusIndex: Int = 0) {
        AlertDialog.Builder(this@SettingsActivity).setTitle("Choose playlist").setItems(playlists.map { it.name }.toTypedArray()) { _, which ->
                val input = android.widget.EditText(this@SettingsActivity).apply { hint = "https://example.com/guide.xml"; setSingleLine() }
                AlertDialog.Builder(this@SettingsActivity).setTitle("Guide URL").setView(input).setNegativeButton("Cancel") { _, _ -> chooseGuidePlaylist(playlists, which) }.setPositiveButton("Assign") { _, _ -> lifecycleScope.launch {
                    if (input.text.isNotBlank()) {
                        appContainer.epg.addSource(input.text.toString(), playlists[which].name, playlists[which].id)
                        android.widget.Toast.makeText(this@SettingsActivity, "Guide assigned", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } }.setOnCancelListener { chooseGuidePlaylist(playlists, which) }.show()
            }.setOnCancelListener { showGuides(Int.MAX_VALUE) }.create().apply {
                setOnShowListener { listView.setSelection(focusIndex.coerceAtMost(listView.count - 1)); listView.requestFocus() }
            }.show()
    }

    override fun onDestroy() {
        view.release()
        super.onDestroy()
    }

    private fun openSection(to: Section) = onRailPick(to)

    private fun rowsFor(name: String, s: AppSettings): List<Row> {
        val all = buildRows(s)
        return when (name) {
            "Playlists" -> listOf(Row("Manage playlists", "Add, edit, refresh and remove streams") { openSection(Section.PLAYLISTS) })
            "Parental" -> all.take(1)
            "General" -> all.filter { it.title in listOf("Channel name cleanup", getString(R.string.setting_resume_last_channel), getString(R.string.setting_multiview_warning)) } + maintenance()
            "EPG" -> all.dropWhile { it.title != getString(R.string.settings_guide) }.filterNot { it.isHeader } + all.filter { it.title == "Full guide scan" } + listOf(Row("Guide sources and assignments", "URLs and last update times") { showGuides() }, Row("Clear EPG", "") { clearEpg() })
            "Player" -> all.dropWhile { it.title != getString(R.string.settings_player) }.drop(1).takeWhile { !it.isHeader }.filterNot { it.title in listOf(getString(R.string.setting_preview_follows_focus), getString(R.string.setting_multiview_warning), getString(R.string.setting_resume_last_channel)) }
            "Appearance" -> all.filter { it.title in listOf(getString(R.string.setting_preview_follows_focus), "Show clock", "Dim past programmes", "Panel transparency") }
            "Recording" -> all.filter { it.title in listOf(getString(R.string.recordings_title), getString(R.string.setting_pad_before), getString(R.string.setting_pad_after), getString(R.string.setting_instant_length)) }
            "Reminders" -> all.filter { it.title in listOf(getString(R.string.reminders_title), getString(R.string.setting_reminder_before), getString(R.string.setting_reminder_popup)) }
            "Backup & restore" -> listOf(Row("Export backup", "Includes credentials; excludes parental PIN") { exportFile.launch("opentelly-backup.json") }, Row("Import backup", "Replace configuration from a versioned JSON file") { importFile.launch(arrayOf("application/json", "text/plain")) })
            "About" -> listOf(Row("OpenTelly TV ${com.opentelly.tv.BuildConfig.VERSION_NAME}", "A player for playlists supplied by you.") {}, Row("Open-source licences", "AndroidX, Media3, Kotlin, Coil, OkHttp and Retrofit") { AlertDialog.Builder(this).setMessage(assets.open("open_source_licences.txt").bufferedReader().use { it.readText() }).setPositiveButton("OK", null).show() })
            else -> emptyList()
        }
    }

    private fun render(s: AppSettings) {
        currentSettings = s
        val list = rowsFor(current, s).filterNot { it.isHeader }
        val descriptions = mapOf(
            "Channel name cleanup" to "Remove configured prefixes and suffixes from channel names.",
            "Show clock" to "Display the clock on the home screen.",
            "Dim past programmes" to "Shade programmes that have already finished.",
            "Panel transparency" to "Choose how much video shows through panels.",
            "Full guide scan" to "Store all guide programmes, including channels outside your playlists.",
            "Audio decoder" to "Choose the preferred decoder for audio playback.",
            "Audio passthrough" to "Send supported audio directly to your receiver.",
            getString(R.string.setting_buffer) to "Increase buffering for streams that pause or stutter.",
            getString(R.string.setting_decoders) to "Choose the preferred decoder for video playback.",
            getString(R.string.setting_tunneling) to "Use supported hardware audio and video synchronisation.",
            getString(R.string.setting_display_mode) to "Choose how video fits the screen.",
            getString(R.string.setting_background_playback) to "Keep playback running when leaving the player.",
            getString(R.string.setting_preview_follows_focus) to "Update the preview as you move between channels.",
            getString(R.string.setting_multiview_warning) to "Remind on opening multiview that each screen uses one provider connection.",
            getString(R.string.setting_guide_interval) to "Choose how often guides refresh automatically.",
            getString(R.string.setting_epg_offset) to "Shift programme times to correct a provider time offset.",
            getString(R.string.setting_store_descriptions) to "Keep programme descriptions for guide details.",
            getString(R.string.setting_update_guides) to "Download and refresh every enabled guide source.",
            getString(R.string.setting_pad_before) to "Start recording before the scheduled programme time.",
            getString(R.string.setting_pad_after) to "Continue recording after the scheduled programme ends.",
            getString(R.string.setting_instant_length) to "Set the default duration for an instant recording.",
            getString(R.string.setting_reminder_before) to "Choose how early a programme reminder appears.",
            getString(R.string.setting_reminder_popup) to "Choose how long a reminder stays on screen.",
            "Clear EPG" to "Delete stored guide programmes while keeping source assignments.",
            "Clear logo cache" to "Remove cached logos so they download again.",
            getString(R.string.recordings_title) to "Open the list of scheduled and finished recordings.",
            getString(R.string.reminders_title) to "Open the list of programme reminders.",
        )
        val explanatory = listOf("Manage playlists", "Parental controls", "Guide sources and assignments", "Export backup", "Import backup", "Open-source licences")
        view.b.title.text = current
        view.b.subtitle.text = SUMMARIES.getOrNull(SECTIONS.indexOf(current)).orEmpty()
        val focused = view.b.list.focusedChild?.let { view.b.list.getChildAdapterPosition(it) }
        rowActions = list.map { it.onClick }
        adapter.submit(list.mapIndexed { i, row ->
            val explanatoryValue = row.title in explanatory || row.title.startsWith("OpenTelly TV ")
            SimpleRow(
                id = i.toLong(),
                title = row.title,
                value = if (explanatoryValue) "" else row.value,
                detail = row.description.ifEmpty { descriptions[row.title].orEmpty() }.ifEmpty { if (explanatoryValue) row.value else "" },
            )
        })
        if (focused != null && focused >= 0) {
            view.b.list.post { view.b.list.findViewHolderForAdapterPosition(focused.coerceAtMost(list.size - 1))?.itemView?.requestFocus() }
        }
    }

    companion object {
        private val SECTIONS = listOf("General", "Playlists", "EPG", "Player", "Appearance", "Recording", "Reminders", "Parental", "Backup & restore", "About")
        private val SUMMARIES = listOf("Channel names, multiview warning and cache maintenance", "Add, edit and refresh your playlists", "Guide sources, matching and refresh options", "Decoders, buffering and playback behaviour", "Clock, guide shading and panel appearance", "Recordings, padding and default duration", "Reminder timing and popup duration", "PIN, blocked channels and unlock duration", "Save or restore your configuration", "Version information and open-source licences")
    }

    private fun minutes(m: Int) = resources.getQuantityString(R.plurals.minutes, m, m)
    private fun seconds(v: Int) = resources.getQuantityString(R.plurals.seconds, v, v)

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
