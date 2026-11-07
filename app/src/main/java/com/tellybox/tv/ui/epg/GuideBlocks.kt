package com.tellybox.tv.ui.epg

import com.tellybox.tv.data.db.EpgProgramEntity
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Turns a channel's programmes into the cells the guide draws, plain JVM so it can be tested. */
object GuideBlocks {
    val SLOT: Long = TimeUnit.MINUTES.toMillis(30)

    fun floorSlot(t: Long) = t - Math.floorMod(t, SLOT)

    /** Bounds of the single strip shown for a channel with no programmes at all. */
    const val STRIP_START = Long.MIN_VALUE / 4
    const val STRIP_END = Long.MAX_VALUE / 4

    /**
     * Cells covering [from, to). [programs] must be sorted by start. A null list means the
     * programmes haven't been loaded yet, an empty one that there are none: both give one
     * strip across the whole row, so the row is still there to move onto and pick. Holes in
     * a channel that does have data are filled with half-hour placeholders instead, so
     * Left/Right keep stepping through time at a steady pace.
     */
    fun build(programs: List<EpgProgramEntity>?, from: Long, to: Long): List<GuideBlock> {
        if (programs.isNullOrEmpty()) {
            val strip = if (programs == null) GuideBlock.Strip.LOADING else GuideBlock.Strip.NO_DATA
            return listOf(GuideBlock(STRIP_START, STRIP_END, null, strip))
        }
        val out = ArrayList<GuideBlock>()
        var cursor = floorSlot(from)
        for (p in programs) {
            if (p.stop <= cursor) continue
            if (p.start >= to) break
            while (cursor < p.start && cursor < to) {
                val end = min(p.start, floorSlot(cursor) + SLOT)
                out += GuideBlock(cursor, end, null)
                cursor = end
            }
            out += GuideBlock(p.start, p.stop, p)
            cursor = p.stop
        }
        while (cursor < to) {
            val end = floorSlot(cursor) + SLOT
            out += GuideBlock(cursor, end, null)
            cursor = end
        }
        return out
    }
}
