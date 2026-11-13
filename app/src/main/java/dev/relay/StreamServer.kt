package dev.relay

import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Minimal HTTP server for the live AAC stream.
 *
 * Each listener gets its own bounded queue and writer thread, so the
 * capture thread never blocks on the network: a stalled speaker can't
 * back up audio capture, and a listener that falls more than
 * [QUEUE_FRAMES] chunks (~6 s) behind is dropped and left to reconnect.
 */
class StreamServer(private val port: Int, private val token: String) {

    private class Client(val socket: Socket) {
        val queue = ArrayBlockingQueue<ByteArray>(QUEUE_FRAMES)
        @Volatile var alive = true

        fun close() {
            alive = false
            try { socket.close() } catch (_: IOException) {}
        }
    }

    private val clients = CopyOnWriteArrayList<Client>()
    private var serverSocket: ServerSocket? = null

    @Volatile private var running = false

    /** Binds the port. Throws if it is unavailable, so the caller can report it. */
    fun start() {
        val socket = ServerSocket()
        // Lets a new session rebind while the previous one's connections
        // are still in TIME_WAIT.
        socket.reuseAddress = true
        // Closing a ServerSocket that another thread is blocked in accept()
        // on releases the port asynchronously, so an immediate restart can
        // briefly see EADDRINUSE. Retry for up to ~1.5 s before giving up.
        var attempt = 0
        while (true) {
            try {
                socket.bind(InetSocketAddress(port))
                break
            } catch (e: BindException) {
                if (++attempt >= 30) {
                    socket.close()
                    throw e
                }
                Thread.sleep(50)
            }
        }
        serverSocket = socket
        running = true
        thread(name = "relay-accept", isDaemon = true) {
            while (running) {
                val s = try { socket.accept() } catch (_: IOException) { break }
                thread(name = "relay-client", isDaemon = true) { serve(s) }
            }
        }
    }

    private fun serve(socket: Socket) {
        var client: Client? = null
        try {
            socket.soTimeout = 5000
            socket.tcpNoDelay = true
            val head = readHead(socket.getInputStream().buffered()) ?: return
            val requestLine = head.substringBefore("\r\n").split(' ')
            val method = requestLine.getOrElse(0) { "" }
            val target = requestLine.getOrElse(1) { "" }
            val out = socket.getOutputStream()

            if ((method != "GET" && method != "HEAD") || !authorized(target)) {
                out.write(status("403 Forbidden"))
                return
            }
            if (clients.size >= MAX_CLIENTS) {
                out.write(status("503 Service Unavailable"))
                return
            }

            out.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: audio/aac\r\n" +
                    "Cache-Control: no-cache, no-store\r\n" +
                    "Connection: close\r\n" +
                    "icy-name: Relay\r\n" +
                    "Server: Relay\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.flush()
            if (method == "HEAD") return

            socket.soTimeout = 0
            val c = Client(socket)
            client = c
            clients.add(c)
            while (running && c.alive) {
                val chunk = c.queue.poll(1, TimeUnit.SECONDS) ?: continue
                out.write(chunk)
            }
        } catch (_: IOException) {
            // Listener hung up or the network dropped.
        } catch (_: InterruptedException) {
        } finally {
            client?.let { clients.remove(it); it.close() }
            try { socket.close() } catch (_: IOException) {}
        }
    }

    /** Only "/stream.aac?t=<this session's token>" is served. */
    internal fun authorized(target: String): Boolean {
        val path = target.substringBefore('?')
        if (path != "/stream.aac") return false
        val query = target.substringAfter('?', "")
        return query.split('&').any { it == "t=$token" }
    }

    fun hasClients(): Boolean = clients.isNotEmpty()

    fun clientCount(): Int = clients.size

    /** Never blocks. [chunk] must not be modified afterwards. */
    fun broadcast(chunk: ByteArray) {
        for (c in clients) {
            if (!c.queue.offer(chunk)) {
                clients.remove(c)
                c.close()
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        for (c in clients) c.close()
        clients.clear()
    }

    private fun status(line: String): ByteArray =
        "HTTP/1.1 $line\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)

    /** Reads request headers up to the blank line; null if malformed or oversized. */
    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        var tail = 0
        while (buf.length < MAX_HEADER) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) return buf.toString()
        }
        return null
    }

    companion object {
        private const val QUEUE_FRAMES = 64
        private const val MAX_CLIENTS = 4
        private const val MAX_HEADER = 8192
    }
}
