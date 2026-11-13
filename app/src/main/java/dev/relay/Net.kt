package dev.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Finds the phone's address on the local (Wi-Fi or Ethernet) network.
 *
 * The original picked the first IPv4 address of any interface, which on a
 * phone with mobile data or a VPN can be the cellular or tunnel address,
 * so the Sonos was handed a stream URL it could never reach.
 */
object Net {

    data class Lan(val network: Network?, val address: Inet4Address, val iface: NetworkInterface?)

    fun lan(context: Context): Lan? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val networks = try { cm.allNetworks } catch (e: SecurityException) { emptyArray() }
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) continue
            val props = cm.getLinkProperties(network) ?: continue
            val address = props.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress } ?: continue
            val iface = props.interfaceName?.let {
                try { NetworkInterface.getByName(it) } catch (e: Exception) { null }
            }
            pin(network)
            return Lan(network, address, iface)
        }
        return fallback()
    }

    /** Route Sonos control traffic over this network, even if it isn't the default. */
    private fun pin(network: Network) {
        SonosControl.lanConnector = { url -> network.openConnection(url) as HttpURLConnection }
    }

    private fun fallback(): Lan? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in interfaces.asSequence()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase()
                if (!n.startsWith("wlan") && !n.startsWith("eth")) continue
                val address = ni.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress } ?: continue
                return Lan(null, address, ni)
            }
        } catch (e: Exception) {
        }
        return null
    }
}
