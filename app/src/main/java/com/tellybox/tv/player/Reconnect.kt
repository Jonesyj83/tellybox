package com.tellybox.tv.player

/**
 * Where a reconnect picks up.
 *
 * A live channel is only ever at the edge, so a dropped connection goes back to the edge — resuming
 * an old second of a live stream would leave the viewer behind for no reason. A recording or a
 * catch-up stream is a fixed file, and dropping the viewer back to the start throws away however
 * long they had been watching, so the player's own position is kept and the last saved position is
 * only the fallback for when the player no longer knows.
 */
internal fun resumeAt(isCatchupOrFile: Boolean, playerPosition: Long?, savedPosition: Long): Long =
    if (!isCatchupOrFile) 0L else playerPosition ?: savedPosition
