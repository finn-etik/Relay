package dev.field.cloud

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The Drime client against a stand-in server that answers as Drime's
 * documentation says Drime does. It checks what PAGE sends (paths, token,
 * form encoding) and how it reads the answers; it cannot check that the
 * documentation is right.
 */
class DrimeTest {
    private lateinit var server: HttpServer
    private lateinit var storage: HttpServer
    private val seen = ArrayList<String>()
    private val bodies = ArrayList<String>()
    private var tooMany = 0

    private fun HttpExchange.reply(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
        close()
    }

    @Before fun start() {
        storage = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        storage.createContext("/blob") { x ->
            seen += "storage auth=" + (x.requestHeaders.getFirst("Authorization") ?: "none")
            x.reply(200, "# Title\n\nBody text.")
        }
        storage.start()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1") { x ->
            val path = x.requestURI.toString().removePrefix("/api/v1")
            val body = String(x.requestBody.readBytes())
            seen += x.requestMethod + " " + path
            bodies += body
            val auth = x.requestHeaders.getFirst("Authorization")
            when {
                auth != "Bearer good" -> x.reply(401, """{"message":"Unauthenticated."}""")
                path == "/cli/loggedUser" -> x.reply(200, """{"user":{"id":1,"email":"finn@example.org"}}""")
                path.startsWith("/drive/file-entries") && "page=1" in path && "folderId=Zm9sZGVy" in path -> x.reply(
                    200,
                    """{"data":[{"id":10,"name":"a.md","type":"text","hash":"aGFzaA","file_size":12,"updated_at":"2026-11-25T10:00:00.000000Z"},
                       {"id":11,"name":"sub","type":"folder","hash":"c3Vi","file_size":0,"updated_at":"2026-11-24T10:00:00.000000Z"}],
                       "current_page":1,"last_page":2}""",
                )
                path.startsWith("/drive/file-entries") && "sharedOnly=true" in path -> x.reply(
                    200, """{"data":[{"id":30,"name":"Home","type":"folder","hash":"aG9tZQ","file_size":0},{"id":31,"name":"x.md","type":"text","hash":"eA","file_size":1}],"current_page":1,"last_page":1}""",
                )
                path == "/file-entries/30/share" -> x.reply(200, """{"status":"success","users":[{"id":2,"email":"p@example.org"}]}""")
                path.startsWith("/drive/file-entries") && "page=2" in path -> x.reply(
                    200, """{"data":[{"id":12,"name":"b.txt","type":"text","hash":"Yg","file_size":3,"created_at":"2026-11-20T10:00:00.000000Z"}],"current_page":2,"last_page":2}""",
                )
                path.startsWith("/drive/file-entries") -> x.reply(200, """{"data":[],"folder":null,"current_page":1,"last_page":1}""")
                path.startsWith("/folders") -> x.reply(200, """{"status":"success","folder":{"id":5,"name":"PAGE","type":"folder","hash":"Zm9sZGVy","file_size":0}}""")
                path == "/uploads" -> x.reply(201, """{"status":"success","fileEntry":{"id":20,"name":"new note.md","type":"text","hash":"bmV3","file_size":9,"parent_id":5}}""")
                path == "/file-entries/download/aGFzaA" -> x.reply(302, "", mapOf("Location" to "http://127.0.0.1:${storage.address.port}/blob?sig=1"))
                path == "/file-entries/download/direct" -> x.reply(200, "direct")
                path == "/file-entries/delete" -> if (tooMany-- > 0) x.reply(429, """{"message":"Too Many Attempts."}""") else x.reply(200, """{"status":"success"}""")
                path == "/file-entries/20" -> x.reply(422, """{"status":"error","message":"The name has already been taken.","errors":{"name":["taken"]}}""")
                else -> x.reply(404, """{"message":"Not found"}""")
            }
        }
        server.start()
    }

    @After fun stop() {
        server.stop(0)
        storage.stop(0)
    }

    private fun drime(token: String = "good") = Drime(token, "http://127.0.0.1:${server.address.port}/api/v1")

    @Test fun readsTheAccountAndRefusesABadToken() {
        assertEquals("finn@example.org", drime().account())
        try {
            drime("bad").account()
            fail("accepted a bad token")
        } catch (e: DrimeException) {
            assertEquals(401, e.code)
            assertEquals("Drime did not accept the token", e.message)
        }
    }

    @Test fun listsEveryPageOfAFolder() {
        val entries = drime().list("Zm9sZGVy")
        assertEquals(listOf("a.md", "sub", "b.txt"), entries.map { it.name })
        assertEquals(DrimeEntry(10, "a.md", false, "aGFzaA", 12, "2026-11-25T10:00:00.000000Z"), entries[0])
        assertTrue(entries[1].folder)
        assertEquals("2026-11-20T10:00:00.000000Z", entries[2].updated)
        assertEquals(2, seen.count { it.startsWith("GET /drive/file-entries") })
        assertTrue(drime().list(null).isEmpty())
        assertFalse(seen.last().contains("folderId"))
    }

    @Test fun uploadsAsAFormWithTheFileAndItsFolder() {
        val entry = drime().upload(5, "new \"note\".md", "# Héllo\n".toByteArray(), 1764064800000)
        assertEquals(20L, entry.id)
        assertEquals("bmV3", entry.hash)
        val form = bodies.last()
        assertTrue(form.contains("name=\"parentId\"\r\n\r\n5\r\n"))
        assertTrue(form.contains("name=\"workspaceId\"\r\n\r\n0\r\n"))
        assertTrue(form.contains("name=\"lastModified\"\r\n\r\n1764064800000\r\n"))
        assertTrue(form.contains("name=\"file\"; filename=\"new 'note'.md\"\r\nContent-Type: text/markdown\r\n\r\n# Héllo\n\r\n--"))
        assertTrue(form.trimEnd().endsWith("--"))
    }

    @Test fun downloadsThroughARedirectWithoutLeakingTheToken() {
        assertEquals("# Title\n\nBody text.", String(drime().download("aGFzaA")))
        assertTrue("storage auth=none" in seen)
        assertEquals("direct", String(drime().download("direct")))
    }

    @Test fun findsSharedFoldersAndShares() {
        assertEquals(listOf("Home"), drime().sharedFolders().map { it.name })
        drime().share(30, listOf("p@example.org"))
        assertEquals("POST /file-entries/30/share", seen.last())
        assertTrue(bodies.last().contains("\"emails\":[\"p@example.org\"]") && bodies.last().contains("\"edit\""))
    }

    @Test fun createsFoldersTrashesAndReportsErrorsInWords() {
        assertEquals(DrimeEntry(5, "PAGE", true, "Zm9sZGVy", 0, ""), drime().createFolder("PAGE"))
        assertTrue(bodies.last().contains("\"parentId\":null"))

        tooMany = 2 // "slow down" twice, then fine
        drime().trash(listOf(10, 11))
        assertEquals(3, seen.count { it == "POST /file-entries/delete" })
        assertTrue(bodies.last().contains("\"entryIds\":[10,11]") && bodies.last().contains("\"deleteForever\":false"))

        try {
            drime().rename(20, "a.md")
            fail("no error")
        } catch (e: DrimeException) {
            assertEquals(422, e.code)
            assertEquals("Drime: The name has already been taken.", e.message)
        }
        try {
            Drime("good", "http://127.0.0.1:1/api/v1").account()
            fail("no error")
        } catch (e: DrimeException) {
            assertEquals(0, e.code)
        }
    }
}
