package dev.field.dial

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Radio Browser (radio-browser.info) catalog search. Station data: public domain (CC0). */
object RadioSearch {
    /** Public mirrors, tried in order until one answers. */
    private val HOSTS = listOf(
        "https://de1.api.radio-browser.info",
        "https://at1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://all.api.radio-browser.info",
    )
    private const val LIMIT = 20
    private const val TIMEOUT_MS = 10_000

    /** Hits for [query], or null if no mirror could be reached (as opposed to "nothing found"). */
    suspend fun search(query: String): List<SearchHit>? = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val path = "/json/stations/search?name=$q&limit=$LIMIT&order=votes&reverse=true&hidebroken=true"
        for (host in HOSTS) {
            try {
                return@withContext fetch(host + path)
            } catch (_: Exception) {
                // Mirror unreachable, or answered with an error: try the next one.
            }
        }
        null
    }

    private fun fetch(url: String): List<SearchHit> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", "DIAL/1.2 (Android web radio player)")
        try {
            // An error status is this mirror's problem, not an empty result.
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP " + conn.responseCode)
            return parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): List<SearchHit> {
        val arr = JSONArray(body)
        val out = ArrayList<SearchHit>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val streamUrl = o.optString("url_resolved").ifEmpty { o.optString("url") }
            if (!Dial.isStreamUrl(streamUrl)) continue
            val name = o.optString("name").trim().uppercase(Locale.ROOT)
            if (name.isEmpty()) continue
            out.add(SearchHit(name, streamUrl, o.optString("country"), o.optInt("bitrate"), o.optString("tags")))
        }
        return out.distinctBy { it.url }.take(LIMIT)
    }
}
