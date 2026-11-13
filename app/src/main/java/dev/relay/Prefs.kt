package dev.relay

import android.content.Context

/** The app's few persisted settings, in one SharedPreferences file. */
object Prefs {

    const val FILE = "relay"

    private const val LAST_NAME = "last_name"
    private const val LAST_IP = "last_ip"
    private const val LAST_UUID = "last_uuid"
    private const val WIFI_LOCK = "wifi_lock"

    /** The speaker the Quick Settings tile relays to. */
    fun lastSpeaker(context: Context): SonosSpeaker? {
        val prefs = prefs(context)
        val name = prefs.getString(LAST_NAME, null) ?: return null
        val ip = prefs.getString(LAST_IP, null) ?: return null
        return SonosSpeaker(name, ip, prefs.getString(LAST_UUID, "") ?: "")
    }

    fun saveLastSpeaker(context: Context, speaker: SonosSpeaker) {
        prefs(context).edit()
            .putString(LAST_NAME, speaker.name)
            .putString(LAST_IP, speaker.ip)
            .putString(LAST_UUID, speaker.uuid)
            .apply()
    }

    fun wifiLock(context: Context): Boolean = prefs(context).getBoolean(WIFI_LOCK, false)

    fun setWifiLock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(WIFI_LOCK, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
