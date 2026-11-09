package com.tellybox.tv.data.recording

import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** Minimal HTTP/1.1 server for tests: one request per connection, longest-prefix routing. */
class TestHttpServer : AutoCloseable {
    class Exchange(
        val path: String,
        private val out: OutputStream,
        /** Request headers, names in lower case. */
        val headers: Map<String, String> = emptyMap(),
    ) {
        private var sent = false

        fun send(body: ByteArray, type: String = "video/mp2t", code: Int = 200) {
            header(code, type, body.size.toLong())
            out.write(body)
        }

        /** Promises [length] bytes but sends only [body], so the client sees the connection drop. */
        fun sendCut(body: ByteArray, length: Long, code: Int = 200, type: String = "video/mp2t") {
            header(code, type, length)
            out.write(body)
        }

        /** Starts an open-ended body; write to [stream] until done. */
        fun startStream(type: String = "video/mp2t"): OutputStream {
            header(200, type, null)
            return out
        }

        private fun header(code: Int, type: String, length: Long?) {
            check(!sent)
            sent = true
            val sb = StringBuilder("HTTP/1.1 $code X\r\nContent-Type: $type\r\nConnection: close\r\n")
            if (length != null) sb.append("Content-Length: $length\r\n")
            sb.append("\r\n")
            out.write(sb.toString().toByteArray())
            out.flush()
        }

        val stream: OutputStream get() = out
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool()
    private val routes = LinkedHashMap<String, (Exchange) -> Unit>()
    val base = "http://127.0.0.1:${socket.localPort}"

    init {
        pool.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                pool.execute { handle(client) }
            }
        }
    }

    fun route(prefix: String, handler: (Exchange) -> Unit) {
        routes[prefix] = handler
    }

    private fun handle(client: Socket) = client.use { s ->
        val input = s.getInputStream().bufferedReader()
        val requestLine = input.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val path = requestLine.split(' ').getOrElse(1) { "/" }.substringBefore('?')
        val handler = routes.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
        val ex = Exchange(path, s.getOutputStream(), headers)
        try {
            if (handler == null) ex.send(ByteArray(0), "text/plain", 404) else handler(ex)
            s.getOutputStream().flush()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        socket.close()
        pool.shutdownNow()
    }
}
