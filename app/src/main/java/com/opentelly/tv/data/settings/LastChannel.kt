package com.opentelly.tv.data.settings

import android.content.Context
import com.opentelly.tv.data.db.ChannelEntity
import com.opentelly.tv.data.db.ContentKind
import com.opentelly.tv.data.db.OpenTellyDatabase
import com.opentelly.tv.data.parental.ParentalRepository

/** A channel by the identity that survives playlist refreshes (row ids don't). */
data class ChannelRef(val playlistId: Long, val channelKey: String)

/** The last live channel played anywhere in the app; one for the whole app, not per playlist. */
class LastChannelStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_channel", Context.MODE_PRIVATE)

    fun save(channel: ChannelEntity) {
        if (channel.kind != ContentKind.LIVE) return
        prefs.edit().putLong("playlist", channel.playlistId).putString("key", channel.channelKey).apply()
    }

    fun get(): ChannelRef? {
        val key = prefs.getString("key", null) ?: return null
        return ChannelRef(prefs.getLong("playlist", -1), key)
    }
}

/** Whether to start the last channel in the home preview when the app opens. */
object ResumeLastChannel {
    enum class Skip { SETTING_OFF, NOTHING_SAVED, CHANNEL_GONE, NOT_LIVE, HIDDEN, PLAYLIST_GONE, PLAYLIST_DISABLED, BLOCKED }

    sealed interface Decision {
        data class Start(val channelId: Long) : Decision
        data class Skipped(val reason: Skip) : Decision
    }

    /**
     * [channel] is what [saved] points at now (null if a refresh removed it); [playlistEnabled]
     * is null when the playlist itself is gone; [groupHidden] means its group is hidden from
     * the lists; [blocked] is the parental answer for it.
     */
    fun decide(
        enabled: Boolean,
        saved: ChannelRef?,
        channel: ChannelEntity?,
        playlistEnabled: Boolean?,
        blocked: Boolean,
        groupHidden: Boolean = false,
    ): Decision {
        fun skip(reason: Skip) = Decision.Skipped(reason)
        return when {
            !enabled -> skip(Skip.SETTING_OFF)
            saved == null -> skip(Skip.NOTHING_SAVED)
            playlistEnabled == null -> skip(Skip.PLAYLIST_GONE)
            !playlistEnabled -> skip(Skip.PLAYLIST_DISABLED)
            channel == null -> skip(Skip.CHANNEL_GONE)
            channel.kind != ContentKind.LIVE -> skip(Skip.NOT_LIVE)
            channel.hidden || groupHidden -> skip(Skip.HIDDEN)
            blocked -> skip(Skip.BLOCKED)
            else -> Decision.Start(channel.id)
        }
    }

    /** Looks the saved channel up and decides; never prompts for a PIN. */
    suspend fun check(enabled: Boolean, store: LastChannelStore, db: OpenTellyDatabase, parental: ParentalRepository): Decision {
        val saved = store.get()
        if (!enabled || saved == null) return decide(enabled, saved, null, null, false)
        val playlist = db.playlists().get(saved.playlistId)
        val channel = playlist?.let { db.channels().findByKey(saved.playlistId, saved.channelKey) }
        val blocked = channel != null &&
            (!parental.allowed(channel, db) || (parental.allScreens && !parental.unlocked))
        val groupHidden = channel?.groupId != null &&
            db.groups().getForPlaylist(channel.playlistId).any { it.id == channel.groupId && it.hidden }
        return decide(enabled, saved, channel, playlist?.enabled, blocked, groupHidden)
    }
}
