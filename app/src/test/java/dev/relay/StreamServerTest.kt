package dev.relay

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

class StreamServerTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val server = StreamServer(port, "secret").also { it.start() }

    @After
    fun tearDown() = server.stop()

    private fun request(line: String): Socket = Socket("127.0.0.1", port).also {
        it.soTimeout = 5000
        it.getOutputStream().write("$line\r\nHost: x\r\nIcy-MetaData: 1\r\n\r\n".toByteArray())
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun awaitClients(n: Int) {
        val until = System.currentTimeMillis() + 3000
        while (server.clientCount() != n && System.currentTimeMillis() < until) Thread.sleep(10)
        assertEquals(n, server.clientCount())
    }

    @Test
    fun rejectsMissingWrongOrPrefixedToken() {
        for (line in listOf(
            "GET /stream.aac HTTP/1.1",
            "GET /stream.aac?t=nope HTTP/1.1",
            "GET /stream.aac?t=secretX HTTP/1.1",
            "GET /other?t=secret HTTP/1.1",
            "POST /stream.aac?t=secret HTTP/1.1"
        )) {
            request(line).use { s ->
                assertTrue(line, readHead(s.getInputStream()).startsWith("HTTP/1.1 403"))
            }
        }
        assertEquals(0, server.clientCount())
    }

    @Test
    fun streamsBroadcastBytesInOrder() {
        request("GET /stream.aac?t=secret HTTP/1.1").use { s ->
            val input = s.getInputStream()
            val head = readHead(input)
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertTrue(head.contains("Content-Type: audio/aac"))
            awaitClients(1)
            server.broadcast(byteArrayOf(1, 2, 3))
            server.broadcast(byteArrayOf(4, 5))
            val got = ByteArray(5)
            var off = 0
            while (off < 5) off += input.read(got, off, 5 - off)
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), got)
        }
        // A hang-up is noticed on the next writes.
        val until = System.currentTimeMillis() + 3000
        while (server.clientCount() > 0 && System.currentTimeMillis() < until) {
            server.broadcast(ByteArray(1024))
            Thread.sleep(20)
        }
        assertEquals(0, server.clientCount())
    }

    @Test
    fun headReturnsHeadersOnly() {
        request("HEAD /stream.aac?t=secret HTTP/1.1").use { s ->
            val input = s.getInputStream()
            assertTrue(readHead(input).startsWith("HTTP/1.1 200"))
            assertEquals(-1, input.read())
        }
        assertEquals(0, server.clientCount())
    }

    @Test
    fun stalledListenerIsDroppedWithoutBlockingBroadcast() {
        val s = request("GET /stream.aac?t=secret HTTP/1.1")
        s.receiveBufferSize = 4096
        readHead(s.getInputStream())
        awaitClients(1)
        val chunk = ByteArray(256 * 1024)
        val start = System.nanoTime()
        repeat(400) { server.broadcast(chunk) } // ~100 MB, never read
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("broadcast blocked for $ms ms", ms < 2000)
        awaitClients(0)
        s.close()
    }

    @Test
    fun portCanBeReboundImmediately() {
        request("GET /stream.aac?t=secret HTTP/1.1").use { s ->
            readHead(s.getInputStream())
            awaitClients(1)
            server.stop()
            val again = StreamServer(port, "secret2")
            again.start() // must not throw BindException
            again.stop()
        }
    }
}
