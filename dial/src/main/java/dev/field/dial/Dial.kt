package dev.field.dial

/** One saved web stream. */
data class Station(val id: Int, val name: String, val url: String)

/** One hit from the Radio Browser catalog. */
data class SearchHit(val name: String, val url: String, val country: String, val bitrate: Int, val tags: String)

/** Playback state machine. */
enum class Phase { OFF, CONNECT, ON, ERR }

/**
 * DIAL's whole state and the pure transitions between states.
 *
 * Listening time is derived from wall-clock instants ([onAirSince]) rather
 * than a ticking counter, so it stays right with the screen off, and a
 * session's airtime carries across automatic reconnects.
 */
data class DialState(
    val stations: List<Station> = emptyList(),
    val selId: Int = -1,
    /** The station being played or reconnected to; null when OFF. */
    val playing: Station? = null,
    val phase: Phase = Phase.OFF,
    /** Reconnects tried since the stream last played. */
    val attempts: Int = 0,
    /** When ON: the instant the current stretch went on air. */
    val onAirSince: Long = 0L,
    /** This session's airtime from stretches that already ended (ms). */
    val airBankedMs: Long = 0L,
    /** Lifetime listening time, the running stretch excluded (ms). */
    val totalBankedMs: Long = 0L,
    val vol: Int = 7,
    val muted: Boolean = false,
    val retry: Boolean = true,
    /** Sleep timer: the instant playback stops by itself; 0 = off. */
    val sleepAt: Long = 0L,
    /** What the stream says is on now (programme or track); "" if it does not say. */
    val nowPlaying: String = "",
) {
    val selected: Station? get() = stations.firstOrNull { it.id == selId }
    val volumeFraction: Float get() = if (muted) 0f else vol / Dial.MAX_VOLUME.toFloat()

    private fun stretch(now: Long): Long = if (phase == Phase.ON) maxOf(0L, now - onAirSince) else 0L

    fun airMs(now: Long): Long = airBankedMs + stretch(now)
    fun totalMs(now: Long): Long = totalBankedMs + stretch(now)
    fun sleepRemainingMs(now: Long): Long = if (sleepAt == 0L) 0L else maxOf(0L, sleepAt - now)

    /** Closes the running stretch into the banked counters. */
    private fun banked(now: Long): DialState {
        val d = stretch(now)
        return copy(airBankedMs = airBankedMs + d, totalBankedMs = totalBankedMs + d, onAirSince = 0L)
    }

    // ---- playback transitions ----

    fun tune(st: Station, now: Long): DialState =
        banked(now).copy(playing = st, selId = st.id, phase = Phase.CONNECT, attempts = 0, airBankedMs = 0L, nowPlaying = "")

    /** An automatic reconnect: same station, airtime carries on, attempts count up. */
    fun reconnect(now: Long): DialState =
        if (playing == null) this else banked(now).copy(phase = Phase.CONNECT, attempts = attempts + 1)

    fun onAir(now: Long): DialState =
        if (phase != Phase.CONNECT) this else copy(phase = Phase.ON, onAirSince = now, attempts = 0)

    /** The stream failed or ended. */
    fun dropped(now: Long): DialState =
        if (phase == Phase.OFF) this else banked(now).copy(phase = Phase.ERR, nowPlaying = "")

    fun stop(now: Long): DialState =
        banked(now).copy(phase = Phase.OFF, playing = null, attempts = 0, airBankedMs = 0L, sleepAt = 0L, nowPlaying = "")

    /** The stream announced a new title. Ignored unless a station is wanted. */
    fun title(text: String): DialState = if (playing == null) this else copy(nowPlaying = text.trim())

    // ---- stations and settings ----

    fun select(id: Int): DialState = copy(selId = id)

    fun add(name: String, url: String): DialState {
        val id = (stations.maxOfOrNull { it.id } ?: 0) + 1
        return copy(stations = stations + Station(id, name.trim().uppercase(), url.trim()))
    }

    fun delete(id: Int): DialState =
        copy(stations = stations.filter { it.id != id }, selId = if (selId == id) -1 else selId)

    /** Moves a station one place up (-1) or down (+1) in the list. */
    fun move(id: Int, by: Int): DialState {
        val from = stations.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in stations.indices) return this
        val list = stations.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(stations = list)
    }

    /**
     * The station [by] places after (+1) or before (-1) [id], wrapping round
     * the list: what "next" and "previous" on a headset mean for a radio.
     * With no current station, next is the first and previous the last.
     */
    fun neighbour(id: Int, by: Int): Station? {
        if (stations.isEmpty()) return null
        val at = stations.indexOfFirst { it.id == id }
        if (at < 0) return if (by >= 0) stations.first() else stations.last()
        return stations[(at + by).mod(stations.size)]
    }

    /** A volume key also lifts the mute. */
    fun volume(delta: Int): DialState = copy(vol = (vol + delta).coerceIn(0, Dial.MAX_VOLUME), muted = false)

    fun sleepIn(now: Long, minutes: Int): DialState = copy(sleepAt = if (minutes <= 0) 0L else now + minutes * 60_000L)
}

/** DIAL's rules that need no Android: validation, retry timing, readout text. Pure. */
object Dial {

    /** First run: a few long-standing public streams. */
    val SEED = listOf(
        Station(1, "SOMA GROOVE", "https://ice1.somafm.com/groovesalad-128-mp3"),
        Station(2, "SOMA DRONE", "https://ice1.somafm.com/dronezone-128-mp3"),
        Station(3, "RADIO PARADISE", "https://stream.radioparadise.com/mp3-128"),
    )

    const val MAX_VOLUME = 10
    val SLEEP_MINUTES = listOf(15, 30, 60)

    fun isStreamUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    fun isSaved(url: String, stations: List<Station>): Boolean =
        stations.any { it.url.equals(url.trim(), ignoreCase = true) }

    /** Why a typed station cannot be saved, or null if it can. */
    fun stationError(name: String, url: String, existing: List<Station>): String? = when {
        name.isBlank() || url.isBlank() -> "ENTER NAME AND URL"
        !isStreamUrl(url.trim()) -> "URL MUST START HTTP"
        isSaved(url, existing) -> "ALREADY SAVED"
        else -> null
    }

    /**
     * Wait before reconnect number [attempt] (0 = the first): 3 s, doubling up
     * to a minute, so a dead stream is not hammered every 3 s for hours.
     */
    fun retryDelayMs(attempt: Int): Long = (3_000L shl attempt.coerceIn(0, 5)).coerceAtMost(60_000L)

    /** "GERMANY / 128 KBPS / JAZZ,SMOOTH" */
    fun meta(hit: SearchHit): String = listOfNotNull(
        hit.country.takeIf { it.isNotBlank() }?.uppercase(),
        if (hit.bitrate > 0) "${hit.bitrate} KBPS" else null,
        hit.tags.takeIf { it.isNotBlank() }?.take(24)?.uppercase(),
    ).joinToString(" / ")
}

/**
 * Playlist links (.pls, .m3u): small text files that point at the real
 * stream. Broadcasters publish these (Sveriges Radio's "direktlänkar" are
 * playlists), but a player needs the address inside. Pure.
 */
object Playlist {

    /** By file extension. `.m3u8` is HLS, which the player handles itself. */
    fun looksLikePlaylist(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".pls") || path.endsWith(".m3u")
    }

    /** An HLS playlist saved under a plain .m3u name: play the link itself, as HLS. */
    fun isHls(text: String): Boolean = "#EXT-X-" in text

    /** The first stream address in a PLS (`File1=http…`) or M3U (one address per line) playlist. */
    fun firstStream(text: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
            val isFileEntry = '=' in line && line.substringBefore('=').trim().lowercase().startsWith("file")
            val candidate = if (isFileEntry) line.substringAfter('=').trim() else line
            if (Dial.isStreamUrl(candidate)) return candidate
        }
        return null
    }
}
