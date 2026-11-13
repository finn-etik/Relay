package dev.relay

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A minimal fake Sonos control endpoint for tests, built on plain sockets
 * (Android unit tests compile against android.jar, which lacks the JDK's
 * com.sun.net.httpserver).
 *
 * [handler] receives the raw SOAPACTION header value and the request body,
 * and returns an HTTP status code and response body.
 */
class FakeSonos(private val handler: (action: String, body: String) -> Pair<Int, String>) : AutoCloseable {

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = server.localPort

    init {
        thread(name = "fake-sonos", isDaemon = true) {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket): Unit = socket.use {
        val input = socket.getInputStream().buffered()

        // Request line + headers, up to the blank line.
        val head = StringBuilder()
        var last4 = 0
        while (last4 != 0x0D0A0D0A) {
            val b = input.read()
            if (b < 0) return
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
        }
        val headers = head.lines().drop(1)
            .filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }

        val (code, reply) = handler(headers["soapaction"] ?: "", body.toString(Charsets.UTF_8))
        val bytes = reply.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $code X\r\n" +
            "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(response.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    override fun close() = server.close()
}
