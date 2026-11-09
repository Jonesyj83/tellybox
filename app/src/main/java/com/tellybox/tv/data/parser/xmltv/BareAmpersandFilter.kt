package com.tellybox.tv.data.parser.xmltv

import java.io.InputStream

/**
 * Escapes any `&` that does not start an XML entity reference, so one stray `&` or HTML
 * `&nbsp;` in a provider's guide cannot make the whole document fatally malformed.
 *
 * Works on bytes, so the parser still detects the encoding from the XML declaration. `&` is
 * 0x26 in UTF-8 and every ISO-8859 / Windows code page guides are served in. CDATA sections
 * are passed through untouched, since a bare `&` is legal there.
 */
internal class BareAmpersandFilter(private val source: InputStream) : InputStream() {
    private val buf = ByteArray(64 * 1024)
    private var pos = 0
    private var lim = 0
    private var eof = false

    private var pending: ByteArray = EMPTY
    private var pendingPos = 0

    private var inCdata = false
    private var cdataOpen = 0
    private var cdataClose = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var i = 0
        while (i < len) {
            if (pendingPos < pending.size) {
                b[off + i++] = pending[pendingPos++]
                continue
            }
            if (pos >= lim) {
                // Hand back what we have rather than block for more.
                if (i > 0 || !fill(1)) break
            }
            val c = buf[pos].toInt() and 0xff
            pos++
            track(c)
            if (c == AMP && !inCdata) escapeIfBare()
            b[off + i++] = c.toByte()
        }
        return if (i == 0) -1 else i
    }

    override fun close() = source.close()

    /** Called just past an `&`: decides whether it starts a reference, queues `amp;` if not. */
    private fun escapeIfBare() {
        fill(LOOKAHEAD)
        val end = minOf(lim, pos + LOOKAHEAD)
        var j = pos
        while (j < end && buf[j].toInt() != SEMI && isRefChar(buf[j].toInt())) j++
        val name = if (j < end && buf[j].toInt() == SEMI) String(buf, pos, j - pos, Charsets.ISO_8859_1) else null
        when {
            name != null && isValidReference(name) -> Unit
            // HTML's non-breaking space is the usual offender; keep its meaning.
            name == "nbsp" -> {
                pos = j + 1
                pending = NBSP; pendingPos = 0
            }
            else -> { pending = AMP_TAIL; pendingPos = 0 }
        }
    }

    /** Makes at least [want] bytes available after [pos] unless the stream ends first. */
    private fun fill(want: Int): Boolean {
        if (lim - pos >= want) return true
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, lim - pos)
            lim -= pos
            pos = 0
        }
        while (!eof && lim - pos < want) {
            val n = source.read(buf, lim, buf.size - lim)
            if (n < 0) eof = true else lim += n
        }
        return lim - pos > 0
    }

    private fun track(b: Int) {
        if (inCdata) {
            when {
                b == ']'.code -> cdataClose = minOf(cdataClose + 1, 2)
                b == '>'.code && cdataClose == 2 -> { inCdata = false; cdataClose = 0 }
                else -> cdataClose = 0
            }
        } else {
            cdataOpen = when {
                b == CDATA_OPEN[cdataOpen].toInt() -> cdataOpen + 1
                b == '<'.code -> 1
                else -> 0
            }
            if (cdataOpen == CDATA_OPEN.size) { inCdata = true; cdataOpen = 0 }
        }
    }

    private fun isRefChar(c: Int) =
        c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '#'.code

    companion object {
        private const val AMP = '&'.code
        private const val SEMI = ';'.code
        private const val LOOKAHEAD = 16
        private val EMPTY = ByteArray(0)
        private val AMP_TAIL = "amp;".toByteArray(Charsets.US_ASCII)
        private val NBSP = "#160;".toByteArray(Charsets.US_ASCII)
        private val CDATA_OPEN = "<![CDATA[".toByteArray(Charsets.US_ASCII)
        private val PREDEFINED = setOf("amp", "lt", "gt", "quot", "apos")
        private val DECIMAL = Regex("#[0-9]+")
        private val HEX = Regex("#x[0-9a-fA-F]+")

        /** The five XML entities, or a numeric character reference. */
        internal fun isValidReference(name: String) =
            name in PREDEFINED || DECIMAL.matches(name) || HEX.matches(name)
    }
}
