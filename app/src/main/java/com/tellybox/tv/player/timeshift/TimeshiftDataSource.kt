package com.tellybox.tv.player.timeshift

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * Plays a [TimeshiftSession]'s buffer. The URI carries the offset to start at; at the newest
 * byte a read waits for more instead of ending, so the stream plays on as it grows.
 */
@OptIn(UnstableApi::class)
class TimeshiftDataSource(private val session: TimeshiftSession) : BaseDataSource(true) {
    private var cursor: TimeshiftBuffer.Cursor? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val start = offsetOf(dataSpec.uri) + dataSpec.position
        cursor = session.buffer.Cursor(start)
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val c = cursor ?: return C.RESULT_END_OF_INPUT
        val n = session.buffer.read(c, buffer, offset, length)
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val c = cursor ?: return
        cursor = null
        uri = null
        c.close()
        transferEnded()
    }

    class Factory(private val session: TimeshiftSession) : DataSource.Factory {
        override fun createDataSource(): DataSource = TimeshiftDataSource(session)
    }

    companion object {
        const val SCHEME = "timeshift"

        fun uriFor(offset: Long): Uri = Uri.parse("$SCHEME://buffer/$offset")

        fun offsetOf(uri: Uri): Long = uri.lastPathSegment?.toLongOrNull() ?: 0L
    }
}

/** Opens provider connections through the same data source stack the player uses for live TV. */
@OptIn(UnstableApi::class)
class DataSourceUpstream(private val factory: DataSource.Factory) : Upstream {
    override fun open(url: String): UpstreamConnection {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec(Uri.parse(url)))
        } catch (e: Exception) {
            runCatching { source.close() }
            throw e
        }
        return object : UpstreamConnection {
            override fun read(target: ByteArray, offset: Int, length: Int): Int {
                val n = source.read(target, offset, length)
                return if (n == C.RESULT_END_OF_INPUT) -1 else n
            }

            override fun close() = source.close()
        }
    }
}
