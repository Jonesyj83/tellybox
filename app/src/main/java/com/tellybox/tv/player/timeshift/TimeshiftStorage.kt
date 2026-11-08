package com.tellybox.tv.player.timeshift

import android.content.Context
import java.io.File

/** What the viewer chose in Settings > Player. */
enum class TimeshiftMode { OFF, PAUSE_ONLY, FULL }

/** Why a live channel is playing without timeshift even though it is switched on. */
sealed class TimeshiftUnavailable {
    /** Not enough room to start a buffer at all. */
    data class LowStorage(val freeBytes: Long) : TimeshiftUnavailable()
    /** Started, but the disk filled up underneath it, so the buffer was dropped. */
    data object StorageRanLow : TimeshiftUnavailable()
    /** The buffer file could not be written. */
    data object WriteFailed : TimeshiftUnavailable()
    /** HLS, DASH, RTMP or anything else that isn't a single MPEG-TS stream. */
    data object UnsupportedStream : TimeshiftUnavailable()
}

/** Space rules for the buffer file, kept apart from the I/O so they can be tested. */
object TimeshiftStorage {
    const val MB = 1024L * 1024L
    const val GB = 1024L * MB

    /** Below this much free space a buffer isn't started. */
    const val MIN_FREE_TO_START = 1536L * MB

    /** Below this while buffering, the buffer is given up so the rest of the system isn't starved. */
    const val MIN_FREE_WHILE_RUNNING = 300L * MB

    /** Never take more than this share of what was free when the channel opened. */
    const val MAX_SHARE_OF_FREE = 0.25

    val SIZES_MB = listOf(500, 1024, 2048)
    const val DEFAULT_SIZE_MB = 1024

    /** How big the buffer for this session may get, or why there can't be one. */
    fun capacity(freeBytes: Long, configuredBytes: Long): Result =
        if (freeBytes < MIN_FREE_TO_START) Result.Unavailable(TimeshiftUnavailable.LowStorage(freeBytes))
        else Result.Capacity(minOf(configuredBytes, (freeBytes * MAX_SHARE_OF_FREE).toLong()))

    sealed class Result {
        data class Capacity(val bytes: Long) : Result()
        data class Unavailable(val reason: TimeshiftUnavailable) : Result()
    }

    fun shouldGiveUp(freeBytes: Long) = freeBytes < MIN_FREE_WHILE_RUNNING

    /** Rough playing time a buffer of [bytes] holds at [bytesPerSecond]. */
    fun minutesFor(bytes: Long, bytesPerSecond: Long): Int =
        if (bytesPerSecond <= 0) 0 else (bytes / bytesPerSecond / 60).toInt()

    fun dir(context: Context) = File(context.filesDir, "timeshift")
}

/**
 * The buffer files on disk. A session's file carries a `.part` suffix while it is open; the
 * names of the open ones are kept here so a sweep running at the same time never deletes a
 * file that is in use.
 */
object TimeshiftFiles {
    const val OPEN_SUFFIX = ".part"
    const val CLOSED_SUFFIX = ".closed"

    private val open = HashSet<String>()

    /** Claims a fresh name in [dir]; the caller creates the file. */
    fun claim(dir: File): File = synchronized(open) {
        var file: File
        do {
            file = File(dir, "session-${System.currentTimeMillis()}-${(Math.random() * 1e6).toInt()}$OPEN_SUFFIX")
        } while (file.name in open || file.exists())
        open += file.name
        file
    }

    fun release(file: File) = synchronized(open) { open -= file.name }

    fun isOpen(name: String) = synchronized(open) { name in open }

    /**
     * Deletes everything in [dir] that no session in this process owns: orphans from a run
     * that was killed, and anything a clean close renamed but didn't get to delete.
     */
    fun sweep(dir: File): Int {
        val files = dir.listFiles() ?: return 0
        var deleted = 0
        for (f in files) {
            // Checked and deleted under the same lock that claim() takes, so a session
            // starting right now can't have its new file removed.
            val gone = synchronized(open) { f.name !in open && (if (f.isDirectory) f.deleteRecursively() else f.delete()) }
            if (gone) deleted++
        }
        if (deleted > 0) android.util.Log.i("Timeshift", "Swept $deleted leftover buffer file(s)")
        return deleted
    }
}
