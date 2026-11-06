package com.openstream.tv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openstream.tv.player.BufferSize
import com.openstream.tv.player.PlayerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the video fills the screen. */
enum class DisplayMode { FIT, FILL, ZOOM, REDUCED }

data class AppSettings(
    val channelNameTokens: String = "",
    val buffer: BufferSize = BufferSize.MEDIUM,
    val softwareDecoders: Boolean = false,
    val tunneling: Boolean = false,
    val guideRefreshHours: Int = 12,
    /** Added to every programme time on import, for guides published in the wrong zone. */
    val epgOffsetMinutes: Int = 0,
    val storeDescriptions: Boolean = true,
    val displayMode: DisplayMode = DisplayMode.FIT,
    /** Minutes to start recording before a programme's listed start. */
    val recordPadBefore: Int = 1,
    /** Minutes to keep recording after a programme's listed end. */
    val recordPadAfter: Int = 5,
    val instantRecordMinutes: Int = 60,
    val reminderMinutes: Int = 2,
    val reminderPopupSeconds: Int = 30,
    /** Keep a live stream going after leaving the player without picture-in-picture. */
    val backgroundPlayback: Boolean = true,
    /** Home screen: preview whichever channel focus rests on, rather than waiting for OK. */
    val previewFollowsFocus: Boolean = false,
) {
    val playerConfig get() = PlayerConfig(buffer, softwareDecoders, tunneling)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val channelNameTokens = stringPreferencesKey("channel_name_tokens")
        val buffer = stringPreferencesKey("buffer")
        val softwareDecoders = booleanPreferencesKey("software_decoders")
        val tunneling = booleanPreferencesKey("tunneling")
        val guideRefreshHours = intPreferencesKey("guide_refresh_hours")
        val epgOffset = intPreferencesKey("epg_offset_minutes")
        val storeDescriptions = booleanPreferencesKey("store_descriptions")
        val displayMode = stringPreferencesKey("display_mode")
        val recordPadBefore = intPreferencesKey("record_pad_before")
        val recordPadAfter = intPreferencesKey("record_pad_after")
        val instantRecordMinutes = intPreferencesKey("instant_record_minutes")
        val reminderMinutes = intPreferencesKey("reminder_minutes")
        val reminderPopupSeconds = intPreferencesKey("reminder_popup_seconds")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val previewFollowsFocus = booleanPreferencesKey("preview_follows_focus")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            channelNameTokens = p[Keys.channelNameTokens].orEmpty(),
            buffer = p[Keys.buffer]?.let { runCatching { BufferSize.valueOf(it) }.getOrNull() } ?: d.buffer,
            softwareDecoders = p[Keys.softwareDecoders] ?: d.softwareDecoders,
            tunneling = p[Keys.tunneling] ?: d.tunneling,
            guideRefreshHours = p[Keys.guideRefreshHours] ?: d.guideRefreshHours,
            epgOffsetMinutes = p[Keys.epgOffset] ?: d.epgOffsetMinutes,
            storeDescriptions = p[Keys.storeDescriptions] ?: d.storeDescriptions,
            displayMode = p[Keys.displayMode]?.let { runCatching { DisplayMode.valueOf(it) }.getOrNull() } ?: d.displayMode,
            recordPadBefore = p[Keys.recordPadBefore] ?: d.recordPadBefore,
            recordPadAfter = p[Keys.recordPadAfter] ?: d.recordPadAfter,
            instantRecordMinutes = p[Keys.instantRecordMinutes] ?: d.instantRecordMinutes,
            reminderMinutes = p[Keys.reminderMinutes] ?: d.reminderMinutes,
            reminderPopupSeconds = p[Keys.reminderPopupSeconds] ?: d.reminderPopupSeconds,
            backgroundPlayback = p[Keys.backgroundPlayback] ?: d.backgroundPlayback,
            previewFollowsFocus = p[Keys.previewFollowsFocus] ?: d.previewFollowsFocus,
        )
    }

    suspend fun get(): AppSettings = settings.first()

    suspend fun setChannelNameTokens(v: String) = store.edit { it[Keys.channelNameTokens] = v }

    suspend fun setBuffer(v: BufferSize) = store.edit { it[Keys.buffer] = v.name }
    suspend fun setSoftwareDecoders(v: Boolean) = store.edit { it[Keys.softwareDecoders] = v }
    suspend fun setTunneling(v: Boolean) = store.edit { it[Keys.tunneling] = v }
    suspend fun setGuideRefreshHours(v: Int) = store.edit { it[Keys.guideRefreshHours] = v }
    suspend fun setEpgOffsetMinutes(v: Int) = store.edit { it[Keys.epgOffset] = v }
    suspend fun setStoreDescriptions(v: Boolean) = store.edit { it[Keys.storeDescriptions] = v }
    suspend fun setDisplayMode(v: DisplayMode) = store.edit { it[Keys.displayMode] = v.name }
    suspend fun setRecordPadBefore(v: Int) = store.edit { it[Keys.recordPadBefore] = v }
    suspend fun setRecordPadAfter(v: Int) = store.edit { it[Keys.recordPadAfter] = v }
    suspend fun setInstantRecordMinutes(v: Int) = store.edit { it[Keys.instantRecordMinutes] = v }
    suspend fun setReminderMinutes(v: Int) = store.edit { it[Keys.reminderMinutes] = v }
    suspend fun setReminderPopupSeconds(v: Int) = store.edit { it[Keys.reminderPopupSeconds] = v }
    suspend fun setBackgroundPlayback(v: Boolean) = store.edit { it[Keys.backgroundPlayback] = v }
    suspend fun setPreviewFollowsFocus(v: Boolean) = store.edit { it[Keys.previewFollowsFocus] = v }
}
