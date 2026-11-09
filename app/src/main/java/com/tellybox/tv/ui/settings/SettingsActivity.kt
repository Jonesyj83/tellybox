package com.tellybox.tv.ui.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tellybox.tv.R
import com.tellybox.tv.appContainer
import com.tellybox.tv.data.settings.AppSettings
import com.tellybox.tv.data.settings.DisplayMode
import com.tellybox.tv.ui.chrome.Category
import com.tellybox.tv.ui.chrome.Section
import com.tellybox.tv.ui.chrome.SectionActivity
import com.tellybox.tv.ui.chrome.SimpleRow
import com.tellybox.tv.ui.chrome.SimpleRowAdapter
import com.tellybox.tv.ui.chrome.TwoColumn
import com.tellybox.tv.player.BufferSize
import com.tellybox.tv.player.timeshift.TimeshiftMode
import com.tellybox.tv.player.timeshift.TimeshiftSession
import com.tellybox.tv.player.timeshift.TimeshiftStorage
import com.tellybox.tv.ui.player.TimeshiftText
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.abs

class SettingsActivity : SectionActivity() {

    override val section = Section.SETTINGS
    private lateinit var view: TwoColumn
    private var rowActions: List<() -> Unit> = emptyList()
    private val adapter = SimpleRowAdapter { row -> rowActions.getOrNull(row.id.toInt())?.invoke() }

    private var guideIdSummary = ""
    private var current = SettingsSection.GENERAL
    private var currentSettings: AppSettings? = null
    private val importFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { it?.let(::importBackupUri) }

    private val exportPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) exportBackup() else AlertDialog.Builder(this)
            .setMessage(R.string.backup_storage_denied).setPositiveButton(R.string.ok, null).show()
    }

    private fun requestBackupExport() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            exportPermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else exportBackup()
    }

    private inner class Row(@param:androidx.annotation.StringRes val id: Int, val value: String, val description: String = "", val dimmed: Boolean = false, val title: String = getString(id), val onClick: () -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = SettingsSection.entries.find { it.name == savedInstanceState?.getString("section") } ?: SettingsSection.GENERAL
        view = TwoColumn(layoutInflater, chrome.sectionContent) { c ->
            current = SettingsSection.valueOf(c.key)
            view.b.list.scrollToPosition(0)
            currentSettings?.let(::render)
        }
        columns = view
        setSectionContent(view.b.root)
        view.b.list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        view.b.list.adapter = adapter
        view.categories.submit(SettingsSection.entries.map { Category(it.name, getString(it.title)) })
        view.categories.activeKey = current.name
        view.b.list.post { view.focusCategories() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // How many channels even carry a tvg-id: without one, no guide can attach.
                appContainer.database.epg().let { epg ->
                    val ids = epg.knownTvgIds(null).size
                    val guideChannels = epg.guideChannelCount()
                    val programmes = epg.guideProgramCount()
                    guideIdSummary = getString(R.string.guide_counts, resources.getQuantityString(R.plurals.count_channel_ids, ids, ids), resources.getQuantityString(R.plurals.count_channels, guideChannels, guideChannels), resources.getQuantityString(R.plurals.count_programmes, programmes, programmes))
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
        outState.putString("section", current.name)
    }

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
            Row(R.string.ui_parental_controls, getString(R.string.ui_pin_blocks_and_unlock_duration)) {
                startActivity(android.content.Intent(this, com.tellybox.tv.ui.parental.ParentalActivity::class.java))
            },
            Row(R.string.setting_language, AppLanguages.current()?.name ?: getString(R.string.language_system)) {
                val languages = listOf(getString(R.string.language_system) to "") + AppLanguages.all.map { it.name to it.tag }
                pick(R.string.setting_language, languages, AppLanguages.current()?.tag.orEmpty(), { tag -> AppLanguages.apply(tag) })
            },
            Row(R.string.ui_channel_name_cleanup, s.channelNameTokens.ifEmpty { getString(R.string.off) }) {
                val input = android.widget.EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 3
                    hint = getString(R.string.ui_one_prefix_or_suffix_per_line_uk_hd_fhd)
                    setText(s.channelNameTokens)
                }
                AlertDialog.Builder(this).setTitle(getString(R.string.ui_strip_prefixes_and_suffixes))
                    .setMessage(getString(R.string.ui_one_literal_token_per_line_matching_is_case_sensitive_clear_to_disable))
                    .setView(input).setPositiveButton(getString(R.string.ui_save)) { _, _ ->
                        lifecycleScope.launch { repo.setChannelNameTokens(input.text.toString()) }
                    }.setNegativeButton(R.string.cancel, null).show()
            },
            Row(R.string.ui_show_clock, onOff(s.showClock)) { lifecycleScope.launch { repo.setShowClock(!s.showClock) } },
            Row(R.string.ui_dim_past_programmes, onOff(s.dimPast)) { lifecycleScope.launch { repo.setDimPast(!s.dimPast) } },
            Row(R.string.ui_panel_transparency, percent(100 - s.panelOpacity)) {
                AlertDialog.Builder(this).setTitle(getString(R.string.ui_panel_transparency)).setItems(arrayOf(getString(R.string.ui_opaque), percent(10), percent(25), percent(40))) { _, i -> lifecycleScope.launch { repo.setPanelOpacity(listOf(100, 90, 75, 60)[i]) } }.show()
            },
            Row(R.string.ui_full_guide_scan, onOff(s.fullScan)) { lifecycleScope.launch { repo.setFullScan(!s.fullScan) } },
            Row(R.string.ui_audio_decoder, if (s.softwareAudioDecoders) getString(R.string.ui_software_preferred) else getString(R.string.ui_hardware_preferred)) {
                AlertDialog.Builder(this).setTitle(getString(R.string.ui_audio_decoder)).setSingleChoiceItems(arrayOf(getString(R.string.ui_hardware_preferred), getString(R.string.ui_software_preferred)), if (s.softwareAudioDecoders) 1 else 0) { dialog, index ->
                    dialog.dismiss()
                    lifecycleScope.launch { repo.setSoftwareAudioDecoders(index == 1) }
                }.show()
            },
            Row(R.string.ui_audio_passthrough, onOff(s.audioPassthrough)) { lifecycleScope.launch { repo.setAudioPassthrough(!s.audioPassthrough) } },
            Row(R.string.setting_buffer, bufferLabel(s.buffer)) {
                pick(R.string.setting_buffer, buffers, s.buffer, repo::setBuffer)
            },
            Row(R.string.setting_decoders, getString(if (s.softwareDecoders) R.string.decoder_software else R.string.decoder_hardware)) {
                pick(
                    R.string.setting_decoders,
                    listOf(getString(R.string.decoder_hardware) to false, getString(R.string.decoder_software) to true),
                    s.softwareDecoders, repo::setSoftwareDecoders,
                )
            },
            Row(R.string.setting_tunneling, onOff(s.tunneling)) {
                pick(R.string.setting_tunneling, onOff, s.tunneling, repo::setTunneling)
            },
            Row(R.string.setting_display_mode, displayModeLabel(s.displayMode)) {
                pick(R.string.setting_display_mode, modes, s.displayMode, repo::setDisplayMode)
            },
            Row(R.string.setting_background_playback, onOff(s.backgroundPlayback)) {
                pick(R.string.setting_background_playback, onOff, s.backgroundPlayback, repo::setBackgroundPlayback)
            },
            *timeshiftRows(s),
            Row(R.string.setting_preview_follows_focus, onOff(s.previewFollowsFocus)) {
                pick(R.string.setting_preview_follows_focus, onOff, s.previewFollowsFocus, repo::setPreviewFollowsFocus)
            },
            Row(R.string.setting_multiview_warning, onOff(s.multiviewConnectionWarning)) {
                pick(R.string.setting_multiview_warning, onOff, s.multiviewConnectionWarning, repo::setMultiviewConnectionWarning)
            },
            Row(R.string.setting_resume_last_channel, onOff(s.resumeLastChannel)) {
                pick(R.string.setting_resume_last_channel, onOff, s.resumeLastChannel, repo::setResumeLastChannel)
            },
            Row(R.string.recordings_title, "") { openSection(Section.RECORDINGS) },
            Row(R.string.reminders_title, "") { openSection(Section.REMINDERS) },
            Row(R.string.setting_pad_before, minutes(s.recordPadBefore)) {
                pick(R.string.setting_pad_before, padOptions, s.recordPadBefore, repo::setRecordPadBefore)
            },
            Row(R.string.setting_pad_after, minutes(s.recordPadAfter)) {
                pick(R.string.setting_pad_after, padOptions, s.recordPadAfter, repo::setRecordPadAfter)
            },
            Row(R.string.setting_instant_length, minutes(s.instantRecordMinutes)) {
                pick(R.string.setting_instant_length, listOf(15, 30, 60, 90, 120, 180, 240).map { minutes(it) to it },
                    s.instantRecordMinutes, repo::setInstantRecordMinutes)
            },
            Row(R.string.setting_reminder_before, minutes(s.reminderMinutes)) {
                pick(R.string.setting_reminder_before, listOf(0, 1, 2, 5, 10, 15, 30).map { minutes(it) to it },
                    s.reminderMinutes, repo::setReminderMinutes)
            },
            Row(R.string.setting_reminder_popup, seconds(s.reminderPopupSeconds)) {
                pick(R.string.setting_reminder_popup, listOf(10, 20, 30, 60, 120).map { seconds(it) to it },
                    s.reminderPopupSeconds, repo::setReminderPopupSeconds)
            },
            Row(R.string.guide_counts, getString(R.string.ui_refresh), title = guideIdSummary) { reimportGuides() },
            Row(R.string.setting_guide_interval, resources.getQuantityString(R.plurals.hours, s.guideRefreshHours, s.guideRefreshHours)) {
                pick(R.string.setting_guide_interval, intervals, s.guideRefreshHours, repo::setGuideRefreshHours)
            },
            Row(R.string.setting_epg_offset, formatOffset(s.epgOffsetMinutes)) {
                pick(R.string.setting_epg_offset, offsets, s.epgOffsetMinutes, repo::setEpgOffsetMinutes, reimportGuides)
            },
            Row(R.string.setting_store_descriptions, onOff(s.storeDescriptions)) {
                pick(R.string.setting_store_descriptions, onOff, s.storeDescriptions, repo::setStoreDescriptions, reimportGuides)
            },
            Row(R.string.setting_update_guides, "") { reimportGuides() },
        )
    }

    /**
     * Timeshift and its buffer size. With too little storage both stay on screen, dimmed,
     * with the reason in place of the value, so it's clear why the option does nothing.
     */
    private fun timeshiftRows(s: AppSettings): Array<Row> {
        val repo = appContainer.settings
        val dir = TimeshiftStorage.dir(this).apply { mkdirs() }
        val session = appContainer.playback.player?.timeshift
        val blocked = when (val plan = TimeshiftStorage.capacity(dir.usableSpace, s.timeshiftBufferMb * TimeshiftStorage.MB)) {
            is TimeshiftStorage.Result.Unavailable -> TimeshiftText.reason(this, plan.reason)
            is TimeshiftStorage.Result.Capacity -> session?.unavailable?.let { TimeshiftText.reason(this, it) }
        }
        val blockedClick = { android.widget.Toast.makeText(this, blocked, android.widget.Toast.LENGTH_LONG).show(); Unit }

        val measured = session?.window()?.bytesPerSecond?.takeIf { it > 0 } ?: TimeshiftSession.lastByteRate
        val rate = measured.takeIf { it > 0 } ?: TYPICAL_HD_BYTES_PER_SECOND
        val channelName = appContainer.playback.player?.takeIf { appContainer.playback.liveChannelId != null }
            ?.exo?.mediaMetadata?.title?.toString()
        val rateNote = when {
            measured > 0 && channelName != null -> getString(R.string.timeshift_rate_channel, channelName)
            measured > 0 -> getString(R.string.timeshift_rate_last)
            else -> getString(R.string.timeshift_rate_typical)
        }
        fun sizeLabel(mb: Int) = getString(R.string.timeshift_size_minutes, TimeshiftText.size(this, mb * TimeshiftStorage.MB),
            TimeshiftStorage.minutesFor(mb * TimeshiftStorage.MB, rate))

        return arrayOf(
            Row(R.string.setting_timeshift, blocked ?: TimeshiftText.mode(this, s.timeshift), dimmed = blocked != null) {
                if (blocked != null) blockedClick()
                else pickSetting(R.string.setting_timeshift, TimeshiftMode.entries.map { TimeshiftText.mode(this, it) to it }, s.timeshift, repo::setTimeshift)
            },
            Row(R.string.setting_timeshift_buffer, blocked ?: sizeLabel(s.timeshiftBufferMb),
                description = getString(R.string.setting_timeshift_buffer_desc) + " " + rateNote, dimmed = blocked != null) {
                if (blocked != null) blockedClick()
                else pickSetting(R.string.setting_timeshift_buffer, TimeshiftStorage.SIZES_MB.map { sizeLabel(it) to it }, s.timeshiftBufferMb, repo::setTimeshiftBufferMb)
            },
        )
    }

    private fun <T> pickSetting(title: Int, options: List<Pair<String, T>>, current: T, save: suspend (T) -> Unit) {
        val index = options.indexOfFirst { it.second == current }
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), index) { d, which ->
                d.dismiss()
                if (which != index) lifecycleScope.launch { save(options[which].second) }
            }.show()
    }

    private fun maintenance() = listOf(Row(R.string.ui_clear_epg, getString(R.string.ui_maintenance)) { clearEpg() }, Row(R.string.ui_clear_logo_cache, getString(R.string.ui_maintenance)) {
        lifecycleScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                coil.Coil.imageLoader(this@SettingsActivity).diskCache?.clear()
                coil.Coil.imageLoader(this@SettingsActivity).memoryCache?.clear()
            }
            android.widget.Toast.makeText(this@SettingsActivity, getString(R.string.ui_logo_cache_cleared), android.widget.Toast.LENGTH_SHORT).show()
        }
    })

    private fun clearEpg() = lifecycleScope.launch {
        val dao = appContainer.database.epg()
        val sources = appContainer.epg.observeSources().let { it.first() }
        val count = sources.sumOf { dao.programCount(it.id) }
        AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.ui_clear_epg_prompt)).setMessage(resources.getQuantityString(R.plurals.clear_epg_confirm, count, count))
            .setNegativeButton(getString(R.string.cancel), null).setPositiveButton(getString(R.string.ui_clear)) { _, _ -> lifecycleScope.launch {
                sources.forEach { dao.deleteProgramsForSource(it.id); dao.deleteChannelsForSource(it.id); dao.setSourceRefreshResult(it.id, 0, null) }
                android.widget.Toast.makeText(this@SettingsActivity, getString(R.string.ui_epg_cleared), android.widget.Toast.LENGTH_SHORT).show()
            } }.show()
    }

    private fun showGuides(focusIndex: Int = 0): kotlinx.coroutines.Job = lifecycleScope.launch {
        val sources = appContainer.database.epg().observeSources().first().filter { it.enabled }
        val playlists = appContainer.database.playlists().getAll()
        val labels = sources.map { source ->
            val updated = if (source.lastUpdated == 0L) getString(R.string.ui_never_updated)
                else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0] ?: java.util.Locale.getDefault()).format(java.util.Date(source.lastUpdated))
            getString(R.string.guide_source_details, source.name, source.url, updated) + source.lastError?.let { " — $it" }.orEmpty()
        } + getString(R.string.ui_assign_guide_to_playlist)
        AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.ui_guide_sources)).setItems(labels.toTypedArray()) { _, index ->
            if (index < sources.size) {
                val source = sources[index]
                AlertDialog.Builder(this@SettingsActivity).setTitle(source.name).setItems(arrayOf(getString(R.string.ui_refresh_now), getString(R.string.ui_remove_guide))) { _, action -> lifecycleScope.launch {
                    if (action == 0) {
                        val result = appContainer.epg.refresh(source)
                        AlertDialog.Builder(this@SettingsActivity).setMessage(result.fold({ it.error ?: getString(R.string.ui_guide_refreshed) }, { getString(R.string.ui_refresh_failed_1_s, it.message.orEmpty()) })).setPositiveButton(getString(R.string.ok), null).show()
                    } else appContainer.epg.deleteSource(source.id)
                } }.setOnCancelListener { showGuides(index) }.show()
            } else chooseGuidePlaylist(playlists)
        }.setPositiveButton(getString(R.string.ui_close), null).create().apply {
            setOnShowListener { listView.setSelection(focusIndex.coerceAtMost(listView.count - 1)); listView.requestFocus() }
        }.show()
    }

    private fun chooseGuidePlaylist(playlists: List<com.tellybox.tv.data.db.PlaylistEntity>, focusIndex: Int = 0) {
        AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.ui_choose_playlist)).setItems(playlists.map { it.name }.toTypedArray()) { _, which ->
                val input = android.widget.EditText(this@SettingsActivity).apply { hint = "https://example.com/guide.xml"; setSingleLine() }
                AlertDialog.Builder(this@SettingsActivity).setTitle(getString(R.string.ui_guide_url)).setView(input).setNegativeButton(getString(R.string.cancel)) { _, _ -> chooseGuidePlaylist(playlists, which) }.setPositiveButton(getString(R.string.ui_assign)) { _, _ -> lifecycleScope.launch {
                    if (input.text.isNotBlank()) {
                        appContainer.epg.addSource(input.text.toString(), playlists[which].name, playlists[which].id)
                        android.widget.Toast.makeText(this@SettingsActivity, getString(R.string.ui_guide_assigned), android.widget.Toast.LENGTH_SHORT).show()
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

    private fun rowsFor(section: SettingsSection, s: AppSettings): List<Row> {
        val rows = section.select(buildRows(s)) { it.id }
        return rows + when (section) {
            SettingsSection.GENERAL -> maintenance()
            SettingsSection.PLAYLISTS -> listOf(Row(R.string.ui_manage_playlists, getString(R.string.ui_add_edit_refresh_and_remove_streams)) { openSection(Section.PLAYLISTS) })
            SettingsSection.EPG -> listOf(Row(R.string.ui_guide_sources_and_assignments, getString(R.string.ui_urls_and_last_update_times)) { showGuides() }, Row(R.string.ui_clear_epg, "") { clearEpg() })
            SettingsSection.BACKUP -> listOf(Row(R.string.ui_export_backup, getString(R.string.ui_includes_credentials_and_parental_pin)) { requestBackupExport() }, Row(R.string.ui_import_backup, getString(R.string.ui_replace_configuration_from_a_versioned_json_file)) { chooseBackup(onChooseFile = { importFile.launch(arrayOf("application/json", "text/plain")) }) })
            SettingsSection.ABOUT -> listOf(Row(R.string.about_version, getString(R.string.ui_a_player_for_playlists_supplied_by_you), title = getString(R.string.about_version, com.tellybox.tv.BuildConfig.VERSION_NAME)) {}, Row(R.string.ui_open_source_licences, getString(R.string.ui_androidx_media3_kotlin_coil_okhttp_and_retrofit)) { AlertDialog.Builder(this).setMessage(assets.open("open_source_licences.txt").bufferedReader().use { it.readText() }).setPositiveButton(R.string.ok, null).show() })
            else -> emptyList()
        }
    }

    private fun render(s: AppSettings) {
        currentSettings = s
        val list = rowsFor(current, s)
        val descriptions = mapOf(
            R.string.ui_channel_name_cleanup to getString(R.string.ui_remove_configured_prefixes_and_suffixes_from_channel_names),
            R.string.ui_show_clock to getString(R.string.ui_display_the_clock_on_the_home_screen),
            R.string.ui_dim_past_programmes to getString(R.string.ui_shade_programmes_that_have_already_finished),
            R.string.ui_panel_transparency to getString(R.string.ui_choose_how_much_video_shows_through_panels),
            R.string.ui_full_guide_scan to getString(R.string.ui_store_all_guide_programmes_including_channels_outside_your_playlists),
            R.string.ui_audio_decoder to getString(R.string.ui_choose_the_preferred_decoder_for_audio_playback),
            R.string.ui_audio_passthrough to getString(R.string.ui_send_supported_audio_directly_to_your_receiver),
            R.string.setting_buffer to getString(R.string.ui_increase_buffering_for_streams_that_pause_or_stutter),
            R.string.setting_decoders to getString(R.string.ui_choose_the_preferred_decoder_for_video_playback),
            R.string.setting_tunneling to getString(R.string.ui_use_supported_hardware_audio_and_video_synchronisation),
            R.string.setting_display_mode to getString(R.string.ui_choose_how_video_fits_the_screen),
            R.string.setting_background_playback to getString(R.string.ui_keep_playback_running_when_leaving_the_player),
            R.string.setting_timeshift to getString(R.string.setting_timeshift_desc),
            R.string.setting_preview_follows_focus to getString(R.string.ui_update_the_preview_as_you_move_between_channels),
            R.string.setting_multiview_warning to getString(R.string.ui_remind_on_opening_multiview_that_each_screen_uses_one_provider_connect),
            R.string.setting_language to getString(R.string.setting_language_desc),
            R.string.setting_guide_interval to getString(R.string.ui_choose_how_often_guides_refresh_automatically),
            R.string.setting_epg_offset to getString(R.string.ui_shift_programme_times_to_correct_a_provider_time_offset),
            R.string.setting_store_descriptions to getString(R.string.ui_keep_programme_descriptions_for_guide_details),
            R.string.setting_update_guides to getString(R.string.ui_download_and_refresh_every_enabled_guide_source),
            R.string.setting_pad_before to getString(R.string.ui_start_recording_before_the_scheduled_programme_time),
            R.string.setting_pad_after to getString(R.string.ui_continue_recording_after_the_scheduled_programme_ends),
            R.string.setting_instant_length to getString(R.string.ui_set_the_default_duration_for_an_instant_recording),
            R.string.setting_reminder_before to getString(R.string.ui_choose_how_early_a_programme_reminder_appears),
            R.string.setting_reminder_popup to getString(R.string.ui_choose_how_long_a_reminder_stays_on_screen),
            R.string.ui_clear_epg to getString(R.string.ui_delete_stored_guide_programmes_while_keeping_source_assignments),
            R.string.ui_clear_logo_cache to getString(R.string.ui_remove_cached_logos_so_they_download_again),
            R.string.recordings_title to getString(R.string.ui_open_the_list_of_scheduled_and_finished_recordings),
            R.string.reminders_title to getString(R.string.ui_open_the_list_of_programme_reminders),
        )
        val explanatory = listOf(R.string.ui_manage_playlists, R.string.ui_parental_controls, R.string.ui_guide_sources_and_assignments, R.string.ui_export_backup, R.string.ui_import_backup, R.string.ui_open_source_licences)
        view.b.title.setText(current.title)
        view.b.subtitle.setText(current.summary)
        val focused = view.b.list.focusedChild?.let { view.b.list.getChildAdapterPosition(it) }
        rowActions = list.map { it.onClick }
        adapter.submit(list.mapIndexed { i, row ->
            val explanatoryValue = row.id in explanatory || row.id == R.string.about_version
            SimpleRow(
                id = i.toLong(),
                title = row.title,
                value = if (explanatoryValue) "" else row.value,
                detail = row.description.ifEmpty { descriptions[row.id].orEmpty() }.ifEmpty { if (explanatoryValue) row.value else "" },
                dimmed = row.dimmed,
            )
        })
        if (focused != null && focused >= 0) {
            view.b.list.post { view.b.list.findViewHolderForAdapterPosition(focused.coerceAtMost(list.size - 1))?.itemView?.requestFocus() }
        }
    }

    companion object {
        /** About 5 Mbit/s, for estimates before any channel has been measured. */
        private const val TYPICAL_HD_BYTES_PER_SECOND = 625_000L
    }

    private fun percent(value: Int) = java.text.NumberFormat.getPercentInstance(androidx.core.os.ConfigurationCompat.getLocales(resources.configuration)[0] ?: java.util.Locale.getDefault()).format(value / 100.0)

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
