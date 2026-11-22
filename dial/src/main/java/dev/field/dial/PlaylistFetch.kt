package dev.field.dial

import java.net.HttpURLConnection
import java.net.URL

/** Downloads a playlist link (a few hundred bytes of text). Blocking: call off the main thread. */
object PlaylistFetch {
    private const val TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_REDIRECTS = 5

    /**
     * The playlist's text, or null if it could not be fetched. Redirects are
     * followed by hand because HttpURLConnection refuses the http -> https
     * hop that broadcasters' old links rely on.
     */
    fun text(url: String): String? = try {
        var current = URL(url)
        var result: String? = null
        for (hop in 0..MAX_REDIRECTS) {
            val conn = current.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "DIAL/1.2 (Android web radio player)")
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: break
                    current = URL(current, location)
                    if (current.protocol != "http" && current.protocol != "https") break
                    continue
                }
                if (code == HttpURLConnection.HTTP_OK) {
                    val bytes = conn.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (out.size() < MAX_BYTES) {
                            val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size()))
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    // Playlists are ASCII in practice; Latin-1 never fails to decode.
                    result = String(bytes, Charsets.ISO_8859_1)
                }
                break
            } finally {
                conn.disconnect()
            }
        }
        result
    } catch (_: Exception) {
        null
    }
}
