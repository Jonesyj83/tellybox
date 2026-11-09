package com.tellybox.tv.ui.settings

import androidx.annotation.StringRes
import com.tellybox.tv.R

/** Navigation and membership never depend on translated titles or the order of rows. */
internal enum class SettingsSection(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    vararg val rowIds: Int,
) {
    GENERAL(R.string.settings_section_general, R.string.settings_summary_general,
        R.string.setting_language,
        R.string.playlist_name_cleanup,
        R.string.setting_resume_last_channel,
        R.string.setting_multiview_warning),
    PLAYLISTS(R.string.playlists_title, R.string.settings_summary_playlists),
    EPG(R.string.settings_section_epg, R.string.settings_summary_epg,
        R.string.guide_counts,
        R.string.setting_guide_interval,
        R.string.setting_epg_offset,
        R.string.setting_store_descriptions,
        R.string.setting_update_guides,
        R.string.guide_full_scan),
    PLAYER(R.string.settings_player, R.string.settings_summary_player,
        R.string.player_audio_decoder,
        R.string.player_audio_passthrough,
        R.string.setting_buffer,
        R.string.setting_decoders,
        R.string.setting_tunneling,
        R.string.setting_display_mode,
        R.string.setting_background_playback,
        R.string.setting_timeshift,
        R.string.setting_timeshift_buffer),
    APPEARANCE(R.string.settings_section_appearance, R.string.settings_summary_appearance,
        R.string.setting_preview_follows_focus,
        R.string.appearance_show_clock,
        R.string.guide_dim_past,
        R.string.appearance_panel_transparency),
    RECORDING(R.string.settings_section_recording, R.string.settings_summary_recording,
        R.string.recordings_title,
        R.string.setting_pad_before,
        R.string.setting_pad_after,
        R.string.setting_instant_length),
    REMINDERS(R.string.reminders_title, R.string.settings_summary_reminders,
        R.string.reminders_title,
        R.string.setting_reminder_before,
        R.string.setting_reminder_popup),
    PARENTAL(R.string.settings_section_parental, R.string.settings_summary_parental,
        R.string.parental_controls),
    BACKUP(R.string.settings_section_backup, R.string.settings_summary_backup),
    ABOUT(R.string.settings_section_about, R.string.settings_summary_about);

    fun <T> select(rows: List<T>, id: (T) -> Int): List<T> = rows.filter { id(it) in rowIds }
}
