package dev.field.dial

/** One saved web stream. */
data class Station(val id: Int, val name: String, val url: String)

/** One hit from the Radio Browser catalog. */
data class SearchHit(val name: String, val url: String, val country: String, val bitrate: Int, val tags: String)

/** Playback state machine. */
enum class Phase { OFF, CONNECT, ON, ERR }

/** DIAL's rules that need no Android: validation, retry timing, readout text. Pure. */
object Dial {

    /** First run: a few long-standing public streams. */
    val SEED = listOf(
        Station(1, "SOMA GROOVE", "https://ice1.somafm.com/groovesalad-128-mp3"),
        Station(2, "SOMA DRONE", "https://ice1.somafm.com/dronezone-128-mp3"),
        Station(3, "RADIO PARADISE", "https://stream.radioparadise.com/mp3-128"),
    )

    const val MAX_VOLUME = 10

    fun isStreamUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** Why a typed station cannot be saved, or null if it can. */
    fun stationError(name: String, url: String, existing: List<Station>): String? = when {
        name.isBlank() || url.isBlank() -> "ENTER NAME AND URL"
        !isStreamUrl(url.trim()) -> "URL MUST START HTTP"
        existing.any { it.url.equals(url.trim(), ignoreCase = true) } -> "ALREADY SAVED"
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
