package com.tellybox.tv.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {
    @Test fun publishesOnlyALoadedPlayerThatIsPlayingOrPaused() {
        assertTrue(SessionPolicy.publish(Player.STATE_READY, hasMedia = true))
        assertTrue(SessionPolicy.publish(Player.STATE_BUFFERING, hasMedia = true))
        assertFalse(SessionPolicy.publish(Player.STATE_IDLE, hasMedia = true))
        assertFalse(SessionPolicy.publish(Player.STATE_ENDED, hasMedia = true))
        assertFalse(SessionPolicy.publish(Player.STATE_READY, hasMedia = false))
        assertFalse(SessionPolicy.publish(Player.STATE_IDLE, hasMedia = false))
    }
}
