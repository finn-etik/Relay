package dev.field.page

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A file or folder in Drime. [hash] is the handle downloads and folder listings use. */
data class DrimeEntry(
    val id: Long,
    val name: String,
    val folder: Boolean,
    val hash: String,
    val size: Long,
    val updated: String,
)

/** A request Drime refused or that never reached it. [code] is the HTTP status, 0 = no answer. */
class DrimeException(val code: Int, message: String) : IOException(message)

/**
 * The few calls PAGE needs from the Drime Cloud API
 * (https://docs.drime.cloud), over plain HttpURLConnection: who am I, list a
 * folder, make a folder, upload, download, rename, move to the trash.
 *
 * Authentication is a personal access token the user creates in Drime
 * (Account Settings, Developers) and pastes into PAGE.
 *
 * Blocking; call from a background thread.
 */
class Drime(private val token: String, private val base: String = "https://app.drime.cloud/api/v1") {

    /** The account's e-mail address. Throws (401) if the token is not accepted. */
    fun account(): String {
        val json = JSONObject(call("GET", "/cli/loggedUser"))
        return (json.optJSONObject("user") ?: json).optString("email").ifEmpty { "DRIME" }
    }

    /** Everything directly inside a folder ([folderHash] null = the top level), all pages. */
    fun list(folderHash: String?): List<DrimeEntry> {
        val out = ArrayList<DrimeEntry>()
        var page = 1
        while (true) {
            val query = "?workspaceId=0&perPage=100&page=$page&orderBy=name&orderDir=asc" +
                if (folderHash != null) "&folderId=" + URLEncoder.encode(folderHash, "UTF-8") else ""
            val json = JSONObject(call("GET", "/drive/file-entries$query"))
            val data = json.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) out += entry(data.getJSONObject(i))
            val last = json.optInt("last_page", page)
            if (page >= last || data.length() == 0 || page >= MAX_PAGES) break
            page++
        }
        return out
    }

    fun createFolder(name: String): DrimeEntry {
        val body = JSONObject().put("name", name).put("parentId", JSONObject.NULL)
        val json = JSONObject(call("POST", "/folders?workspaceId=0", body.toString()))
        return entry(json.optJSONObject("folder") ?: throw DrimeException(0, "Drime did not return the new folder"))
    }

    /** Uploads [bytes] as [name] into folder [parentId]. Returns the entry Drime created. */
    fun upload(parentId: Long, name: String, bytes: ByteArray, modified: Long): DrimeEntry {
        val boundary = "page-" + java.lang.Long.toHexString(System.nanoTime())
        val out = ByteArrayOutputStream(bytes.size + 512)
        fun field(key: String, value: String) =
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n".toByteArray())
        field("parentId", parentId.toString())
        field("workspaceId", "0")
        field("lastModified", modified.toString())
        val mime = if (name.endsWith(".txt", ignoreCase = true)) "text/plain" else "text/markdown"
        out.write(
            ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${name.replace("\"", "'")}\"\r\n" +
                "Content-Type: $mime\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.write("\r\n--$boundary--\r\n".toByteArray())
        val json = JSONObject(call("POST", "/uploads", out.toByteArray(), "multipart/form-data; boundary=$boundary"))
        return entry(json.optJSONObject("fileEntry") ?: throw DrimeException(0, "Drime did not return the uploaded file"))
    }

    /**
     * A file's bytes. Drime may answer with the file or send us on to its
     * storage; the second request goes there without our token (storage
     * rejects a request that carries two kinds of credentials).
     */
    fun download(hash: String, limit: Int = 4_000_000): ByteArray {
        var url = base + "/file-entries/download/" + URLEncoder.encode(hash, "UTF-8")
        var authorised = true
        repeat(5) {
            val c = open("GET", url, authorised)
            c.instanceFollowRedirects = false
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw DrimeException(code, "Redirect without a destination")
                    val target = URL(URL(url), next)
                    authorised = authorised && target.authority == URL(base).authority
                    url = target.toString()
                    return@repeat
                }
                if (code !in 200..299) throw failure(c, code)
                return c.inputStream.use { read(it, limit) }
            } catch (e: DrimeException) {
                throw e
            } catch (e: IOException) {
                throw DrimeException(0, "No connection to Drime")
            } finally {
                c.disconnect()
            }
        }
        throw DrimeException(0, "Too many redirects")
    }

    fun rename(id: Long, name: String) {
        call("PUT", "/file-entries/$id", JSONObject().put("name", name).toString())
    }

    /** Moves entries to Drime's trash, from where they can be restored. */
    fun trash(ids: List<Long>) {
        if (ids.isEmpty()) return
        call("POST", "/file-entries/delete", JSONObject().put("entryIds", JSONArray(ids)).put("deleteForever", false).toString())
    }

    // ------------------------------------------------------------ plumbing

    private fun entry(o: JSONObject) = DrimeEntry(
        id = o.optLong("id"),
        name = o.optString("name"),
        folder = o.optString("type") == "folder",
        hash = o.optString("hash"),
        size = o.optLong("file_size"),
        updated = o.optString("updated_at").ifEmpty { o.optString("created_at") },
    )

    private fun open(method: String, url: String, authorised: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "PAGE (Field kit)")
        if (authorised) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun call(method: String, path: String, json: String) = call(method, path, json.toByteArray(), "application/json")

    /** One API call; a "slow down" answer (429) is retried after 1, 2 and 4 seconds, as Drime's docs ask. */
    private fun call(method: String, path: String, body: ByteArray? = null, type: String? = null): String {
        var wait = 1000L
        while (true) {
            val c = open(method, base + path)
            try {
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", type)
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                if (code == 429 && wait <= 4000) {
                    Thread.sleep(wait)
                    wait *= 2
                    continue
                }
                if (code !in 200..299) throw failure(c, code)
                return String(c.inputStream.use { read(it, 8_000_000) }, Charsets.UTF_8)
            } catch (e: DrimeException) {
                throw e
            } catch (e: IOException) {
                throw DrimeException(0, "No connection to Drime")
            } finally {
                c.disconnect()
            }
        }
    }

    private fun failure(c: HttpURLConnection, code: Int): DrimeException {
        val text = runCatching { c.errorStream?.use { String(read(it, 100_000), Charsets.UTF_8) } }.getOrNull().orEmpty()
        val said = runCatching { JSONObject(text).optString("message") }.getOrDefault("")
        return DrimeException(
            code,
            when {
                code == 401 -> "Drime did not accept the token"
                code == 403 -> "The token may not do this" + if (said.isNotEmpty()) " ($said)" else ""
                code == 429 -> "Drime asks to slow down; try again in a minute"
                said.isNotEmpty() -> "Drime: $said"
                else -> "Drime answered $code"
            },
        )
    }

    private fun read(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) throw DrimeException(0, "File too large")
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_PAGES = 50
    }
}
