package dev.relay

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sonos local UPnP control (port 1400). Fire-and-forget commands run on a
 * single serial thread, so they reach the speaker in the order they were
 * issued (a Stop for the old session can never land after the Play for a
 * new one). Blocking variants are for callers already off the main thread.
 */
object SonosControl {

    private const val AV = "urn:schemas-upnp-org:service:AVTransport:1"
    private const val RC = "urn:schemas-upnp-org:service:RenderingControl:1"
    private const val ZGT = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
    private const val AV_PATH = "/MediaRenderer/AVTransport/Control"
    private const val RC_PATH = "/MediaRenderer/RenderingControl/Control"
    private const val ZGT_PATH = "/ZoneGroupTopology/Control"

    /**
     * Opens connections pinned to the Wi-Fi network (set by [Net]). Needed
     * when mobile data is the default route or a VPN is up. If pinned
     * connections fail, the default route is tried as a fallback.
     */
    @Volatile var lanConnector: ((URL) -> HttpURLConnection)? = null

    /** Sonos control port. Only changed by tests. */
    @Volatile var port = 1400

    private val serial = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sonos-control").apply { isDaemon = true }
    }

    private class SoapFault(val code: Int) : Exception("HTTP $code")

    // ---- Fire-and-forget (serialized) ----

    fun playStream(ip: String, httpUrl: String, title: String, onResult: (Boolean) -> Unit = {}) {
        serial.execute { onResult(playStreamBlocking(ip, httpUrl, title)) }
    }

    fun stop(ip: String) {
        serial.execute { call(ip, AV_PATH, AV, "Stop", "<InstanceID>0</InstanceID>") }
    }

    fun setVolume(ip: String, volume: Int) {
        serial.execute {
            call(
                ip, RC_PATH, RC, "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                    "<DesiredVolume>${volume.coerceIn(0, 100)}</DesiredVolume>"
            )
        }
    }

    /** Relative nudge; reports the speaker's resulting volume (0-100). */
    fun adjustVolume(ip: String, delta: Int, onResult: (Int) -> Unit = {}) {
        serial.execute {
            val body = call(
                ip, RC_PATH, RC, "SetRelativeVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>" +
                    "<Adjustment>$delta</Adjustment>"
            )
            body?.let { tag(it, "NewVolume")?.toIntOrNull() }?.let(onResult)
        }
    }

    fun getVolume(ip: String, onResult: (Int) -> Unit) {
        serial.execute {
            val body = call(
                ip, RC_PATH, RC, "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>"
            )
            body?.let { tag(it, "CurrentVolume")?.toIntOrNull() }?.let(onResult)
        }
    }

    // ---- Blocking ----

    fun playStreamBlocking(ip: String, httpUrl: String, title: String): Boolean {
        val uri = radioUri(httpUrl)
        val withMeta = setUri(ip, uri, didl(title))
        // Metadata only gives the Sonos app a nice title; never let it be
        // the reason playback fails.
        if (!withMeta && !setUri(ip, uri, "")) return false
        return call(ip, AV_PATH, AV, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>") != null
    }

    /** The speaker's current transport URI: "" if none, null if unreachable. */
    fun currentUri(ip: String): String? {
        val body = call(ip, AV_PATH, AV, "GetMediaInfo", "<InstanceID>0</InstanceID>") ?: return null
        return xmlUnescape(tag(body, "CurrentURI") ?: "")
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, or null if unreachable. */
    fun transportState(ip: String): String? {
        val body = call(ip, AV_PATH, AV, "GetTransportInfo", "<InstanceID>0</InstanceID>") ?: return null
        return tag(body, "CurrentTransportState") ?: ""
    }

    /** Playable groups as seen by the speaker at [ip], or null on failure. */
    fun zoneGroups(ip: String): List<SonosSpeaker>? {
        val body = call(ip, ZGT_PATH, ZGT, "GetZoneGroupState", "") ?: return null
        val state = tag(body, "ZoneGroupState") ?: return null
        return parseZoneGroups(xmlUnescape(state)).ifEmpty { null }
    }

    // ---- Pure helpers (unit-tested) ----

    /**
     * http://host:port/path -> x-rincon-mp3radio://host:port/path, which
     * makes Sonos treat the URL as a live radio stream. The scheme replaces
     * "http"; it is not prepended to the full URL.
     */
    fun radioUri(httpUrl: String): String {
        val colon = httpUrl.indexOf(':')
        require(colon > 0) { "Not a URL: $httpUrl" }
        return "x-rincon-mp3radio" + httpUrl.substring(colon)
    }

    fun didl(title: String): String =
        "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\" " +
            "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item id=\"R:0/0/0\" parentID=\"R:0/0\" restricted=\"true\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
            "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">" +
            "SA_RINCON65031_</desc></item></DIDL-Lite>"

    /**
     * Parses the (already unescaped) ZoneGroupState document into one entry
     * per group, addressed to the group coordinator. Hidden members (the
     * second speaker of a stereo pair, surrounds, subs) and Bridges are
     * skipped, so each room appears once and every entry can accept a
     * stream.
     */
    fun parseZoneGroups(state: String): List<SonosSpeaker> {
        val groupRe = Regex("<ZoneGroup\\s([^>]*?)(?<!/)>(.*?)</ZoneGroup>", RegexOption.DOT_MATCHES_ALL)
        val memberRe = Regex("<ZoneGroupMember\\s([^>]*?)/?>")
        val ipRe = Regex("//([0-9.]+)")
        val result = mutableListOf<SonosSpeaker>()
        for (group in groupRe.findAll(state)) {
            val coordinator = attrs(group.groupValues[1])["Coordinator"] ?: continue
            val members = memberRe.findAll(group.groupValues[2])
                .map { attrs(it.groupValues[1]) }
                .filter { it["Invisible"] != "1" && it["IsZoneBridge"] != "1" }
                .toList()
            val coord = members.firstOrNull { it["UUID"] == coordinator } ?: continue
            val ip = coord["Location"]?.let { ipRe.find(it)?.groupValues?.get(1) } ?: continue
            val name = coord["ZoneName"]?.takeIf { it.isNotBlank() } ?: "Sonos"
            val others = members
                .filter { it !== coord }
                .mapNotNull { it["ZoneName"] }
                .filter { it != name }
                .distinct()
            val detail = if (others.isEmpty()) ip else "+ " + others.joinToString(", ")
            result += SonosSpeaker(name, ip, coordinator, detail)
        }
        return result.sortedBy { it.name.lowercase() }
    }

    fun tag(body: String, name: String): String? =
        Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
            ?: if (body.contains("<$name/>")) "" else null

    fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** Single-level unescape; &amp; goes last so "&amp;lt;" stays "&lt;". */
    fun xmlUnescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&amp;", "&")

    private fun attrs(text: String): Map<String, String> =
        Regex("([\\w:]+)=\"([^\"]*)\"").findAll(text)
            .associate { it.groupValues[1] to xmlUnescape(it.groupValues[2]) }

    // ---- Transport ----

    private fun setUri(ip: String, uri: String, meta: String): Boolean = call(
        ip, AV_PATH, AV, "SetAVTransportURI",
        "<InstanceID>0</InstanceID>" +
            "<CurrentURI>${xmlEscape(uri)}</CurrentURI>" +
            "<CurrentURIMetaData>${xmlEscape(meta)}</CurrentURIMetaData>"
    ) != null

    private fun envelope(urn: String, action: String, args: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"$urn\">$args</u:$action></s:Body>" +
            "</s:Envelope>"

    /** POSTs one SOAP action. Returns the response body, or null on any failure. */
    fun call(ip: String, path: String, urn: String, action: String, args: String): String? {
        val body = envelope(urn, action, args).toByteArray(Charsets.UTF_8)
        val url = URL("http://$ip:$port$path")
        val openers = listOfNotNull(lanConnector, { u: URL -> u.openConnection() as HttpURLConnection })
        for (open in openers) {
            try {
                return post(open(url), urn, action, body)
            } catch (_: SoapFault) {
                return null // The speaker answered and refused; retrying won't help.
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
        }
        return null
    }

    private fun post(conn: HttpURLConnection, urn: String, action: String, body: ByteArray): String {
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", "\"$urn#$action\"")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                try { conn.errorStream?.close() } catch (_: IOException) {}
                throw SoapFault(code)
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
