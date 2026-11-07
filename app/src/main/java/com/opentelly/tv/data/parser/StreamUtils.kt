package com.opentelly.tv.data.parser

import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Wraps [input] in a gzip decoder when the data starts with the gzip magic bytes.
 * Providers aren't consistent about file extensions or Content-Encoding headers,
 * so sniffing the first two bytes is the only reliable check.
 */
fun InputStream.decompressIfGzip(): InputStream {
    val buffered = if (this is BufferedInputStream) this else BufferedInputStream(this, 64 * 1024)
    buffered.mark(2)
    val b1 = buffered.read()
    val b2 = buffered.read()
    buffered.reset()
    return if (b1 == 0x1f && b2 == 0x8b) {
        BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024)
    } else {
        buffered
    }
}
