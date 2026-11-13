package dev.relay

/**
 * One playable Sonos target. With topology discovery this is a group
 * coordinator (a stereo pair or home-theatre set shows up once), and
 * [uuid] is its stable RINCON id, which survives DHCP address changes.
 */
data class SonosSpeaker(
    val name: String,
    val ip: String,
    val uuid: String = "",
    val detail: String = ""
)
