package com.opentelly.tv.data.settings

import com.opentelly.tv.data.db.ChannelEntity
import com.opentelly.tv.data.db.ContentKind
import com.opentelly.tv.data.settings.ResumeLastChannel.Decision
import com.opentelly.tv.data.settings.ResumeLastChannel.Skip
import org.junit.Assert.assertEquals
import org.junit.Test

class ResumeLastChannelTest {
    private val saved = ChannelRef(playlistId = 3, channelKey = "live:42")
    private val channel = ChannelEntity(id = 900, playlistId = 3, channelKey = "live:42", name = "News", streamUrl = "http://x/42")

    private fun decide(
        enabled: Boolean = true,
        saved: ChannelRef? = this.saved,
        channel: ChannelEntity? = this.channel,
        playlistEnabled: Boolean? = true,
        blocked: Boolean = false,
        groupHidden: Boolean = false,
    ) = ResumeLastChannel.decide(enabled, saved, channel, playlistEnabled, blocked, groupHidden)

    @Test fun startsTheSavedChannelByItsCurrentId() {
        // Row ids change on refresh; the decision uses whatever id the key resolves to now.
        assertEquals(Decision.Start(900), decide())
    }

    @Test fun skipsWhenTheSettingIsOff() {
        assertEquals(Decision.Skipped(Skip.SETTING_OFF), decide(enabled = false))
    }

    @Test fun skipsWhenNothingWasEverPlayed() {
        assertEquals(Decision.Skipped(Skip.NOTHING_SAVED), decide(saved = null, channel = null))
    }

    @Test fun skipsWhenTheChannelNoLongerExists() {
        assertEquals(Decision.Skipped(Skip.CHANNEL_GONE), decide(channel = null))
    }

    @Test fun skipsWhenThePlaylistWasDeleted() {
        assertEquals(Decision.Skipped(Skip.PLAYLIST_GONE), decide(playlistEnabled = null, channel = null))
    }

    @Test fun skipsWhenThePlaylistIsDisabled() {
        assertEquals(Decision.Skipped(Skip.PLAYLIST_DISABLED), decide(playlistEnabled = false))
    }

    @Test fun skipsWhenParentalControlsBlockIt() {
        assertEquals(Decision.Skipped(Skip.BLOCKED), decide(blocked = true))
    }

    @Test fun skipsHiddenChannelsAndHiddenGroups() {
        assertEquals(Decision.Skipped(Skip.HIDDEN), decide(channel = channel.copy(hidden = true)))
        assertEquals(Decision.Skipped(Skip.HIDDEN), decide(groupHidden = true))
    }

    @Test fun skipsAnythingThatIsNotLive() {
        assertEquals(Decision.Skipped(Skip.NOT_LIVE), decide(channel = channel.copy(kind = ContentKind.MOVIE)))
    }

    @Test fun settingOffWinsOverEverythingElse() {
        assertEquals(Decision.Skipped(Skip.SETTING_OFF), decide(enabled = false, blocked = true, playlistEnabled = false))
    }
}
