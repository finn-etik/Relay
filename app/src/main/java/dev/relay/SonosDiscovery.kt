package dev.relay

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * SSDP discovery. Blocking: call from a background dispatcher.
 *
 * The first speaker that answers is asked for the household topology
 * (ZoneGroupTopology), which lists every group once, addressed to its
 * coordinator. That usually finishes well under a second, and it means
 * stereo pairs, surrounds and grouped rooms no longer show up as
 * duplicates or as targets that refuse the stream.
 */
object SonosDiscovery {

    private const val PREFS = "relay"

    fun lastSpeaker(context: Context): SonosSpeaker? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ip = prefs.getString("last_ip", null) ?: return null
        val name = prefs.getString("last_name", null) ?: return null
        return SonosSpeaker(name, ip, prefs.getString("last_uuid", "") ?: "")
    }

    fun saveLast(context: Context, speaker: SonosSpeaker) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("last_ip", speaker.ip)
            .putString("last_name", speaker.name)
            .putString("last_uuid", speaker.uuid)
            .apply()
    }

    fun discover(context: Context, timeoutMs: Int = 4000): List<SonosSpeaker> {
        val lan = Net.lan(context) ?: return emptyList()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val multicastLock = wifi.createMulticastLock("relay_ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        val fallback = LinkedHashMap<String, SonosSpeaker>()
        val seen = HashSet<String>()
        try {
            MulticastSocket(null as java.net.SocketAddress?).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(lan.address, 0))
                // Pin to Wi-Fi so a VPN or mobile-data default route can't
                // swallow the multicast probe. Must follow bind().
                lan.network?.let { network ->
                    try { network.bindSocket(socket) } catch (e: Exception) { }
                }
                lan.iface?.let { try { socket.networkInterface = it } catch (e: Exception) { } }
                socket.soTimeout = 300

                val search = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 1\r\n" +
                        "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
                        "\r\n"
                    ).toByteArray(Charsets.ISO_8859_1)
                val target = InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900)
                fun probe() = socket.send(DatagramPacket(search, search.size, target))
                probe()
                probe()

                val start = System.currentTimeMillis()
                val deadline = start + timeoutMs
                var resent = false
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    if (!resent && System.currentTimeMillis() - start > 1200) {
                        probe() // UDP is lossy; one late retry.
                        resent = true
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val text = String(buffer, 0, packet.length, Charsets.ISO_8859_1)
                    val location = Regex("(?im)^location:\\s*(\\S+)").find(text)
                        ?.groupValues?.get(1) ?: continue
                    val ip = Regex("//([0-9.]+)").find(location)?.groupValues?.get(1) ?: continue
                    if (!seen.add(ip)) continue

                    SonosControl.zoneGroups(ip)?.let { return it }
                    // Topology unavailable: remember the device itself.
                    fallback.putIfAbsent(ip, describe(ip))
                }
            }
        } catch (e: Exception) {
        } finally {
            try { multicastLock.release() } catch (e: Exception) { }
        }
        // Fallback list: de-duplicate rooms (stereo pairs answer twice).
        return fallback.values
            .distinctBy { it.name }
            .sortedBy { it.name.lowercase() }
    }

    private fun describe(ip: String): SonosSpeaker {
        // A cheap read of the room name; failure still yields a usable entry.
        val body = try {
            val url = java.net.URL("http://$ip:1400/xml/device_description.xml")
            val conn = (SonosControl.lanConnector?.invoke(url)
                ?: url.openConnection() as java.net.HttpURLConnection)
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            try {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            ""
        }
        val room = SonosControl.tag(body, "roomName")?.trim()?.let(SonosControl::xmlUnescape)
        val uuid = SonosControl.tag(body, "UDN")?.removePrefix("uuid:") ?: ""
        return SonosSpeaker(room?.takeIf { it.isNotEmpty() } ?: "Sonos $ip", ip, uuid, ip)
    }
}
