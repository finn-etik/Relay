package dev.relay

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Finds the Sonos speakers on the Wi-Fi network. Blocking: call it from a
 * background dispatcher.
 *
 * 1. Broadcast an SSDP search for Sonos "ZonePlayer" devices.
 * 2. Ask the first speaker that answers for the household topology. That
 *    lists every room/group once, addressed to the speaker that leads it,
 *    so stereo pairs, surrounds and grouped rooms never appear as
 *    duplicates or as targets that refuse the stream. Usually < 1 s.
 * 3. If no speaker answers the topology query, fall back to a plain list of
 *    the devices that replied, one per room name.
 */
object SonosDiscovery {

    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900

    private val SEARCH = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n" +
            "\r\n"
        ).toByteArray(Charsets.ISO_8859_1)

    fun discover(context: Context, timeoutMs: Int = 4000): List<SonosSpeaker> {
        val lan = Net.lan(context) ?: return emptyList()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // Android drops incoming multicast/SSDP traffic without this lock.
        val multicastLock = wifi.createMulticastLock("relay_ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        val fallback = LinkedHashMap<String, SonosSpeaker>()
        try {
            MulticastSocket(null as SocketAddress?).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(lan.address, 0))
                // Pin to Wi-Fi so a VPN or mobile-data default route can't
                // swallow the probe. Must come after bind().
                lan.network?.let { try { it.bindSocket(socket) } catch (_: Exception) {} }
                lan.iface?.let { try { socket.networkInterface = it } catch (_: Exception) {} }
                socket.soTimeout = 300

                val target = InetSocketAddress(InetAddress.getByName(SSDP_HOST), SSDP_PORT)
                fun probe() = socket.send(DatagramPacket(SEARCH, SEARCH.size, target))
                probe()
                probe()

                val start = System.currentTimeMillis()
                var resent = false
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() - start < timeoutMs) {
                    if (!resent && System.currentTimeMillis() - start > 1200) {
                        probe() // UDP is lossy: one late retry
                        resent = true
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val ip = speakerIp(String(buffer, 0, packet.length, Charsets.ISO_8859_1)) ?: continue
                    if (ip in fallback) continue

                    SonosControl.zoneGroups(ip)?.let { return it } // the normal, fast path
                    fallback[ip] = describe(ip)
                }
            }
        } catch (_: Exception) {
            // Network error: return whatever was found.
        } finally {
            try { multicastLock.release() } catch (_: Exception) {}
        }
        return fallback.values
            .distinctBy { it.name } // stereo pairs answer twice
            .sortedBy { it.name.lowercase() }
    }

    /** The speaker's IP from the LOCATION header of an SSDP reply. */
    private fun speakerIp(reply: String): String? {
        val location = Regex("(?im)^location:\\s*(\\S+)").find(reply)?.groupValues?.get(1) ?: return null
        return Regex("//([0-9.]+)").find(location)?.groupValues?.get(1)
    }

    /** Fallback: read one device's room name from its description file. */
    private fun describe(ip: String): SonosSpeaker {
        val body = try {
            val url = URL("http://$ip:1400/xml/device_description.xml")
            val conn = SonosControl.lanConnector?.invoke(url) ?: (url.openConnection() as HttpURLConnection)
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            try {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            ""
        }
        val room = SonosControl.tag(body, "roomName")?.trim()?.let(SonosControl::xmlUnescape)
        val uuid = SonosControl.tag(body, "UDN")?.removePrefix("uuid:") ?: ""
        return SonosSpeaker(room?.takeIf { it.isNotEmpty() } ?: "Sonos $ip", ip, uuid, ip)
    }
}
