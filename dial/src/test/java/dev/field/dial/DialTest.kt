package dev.field.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialTest {

    @Test fun stationValidation() {
        val saved = listOf(Station(1, "A", "https://a.example/stream"))
        assertEquals("ENTER NAME AND URL", Dial.stationError(" ", "https://x", saved))
        assertEquals("ENTER NAME AND URL", Dial.stationError("X", "", saved))
        assertEquals("URL MUST START HTTP", Dial.stationError("X", "ftp://x", saved))
        assertEquals("ALREADY SAVED", Dial.stationError("X", " HTTPS://A.example/stream ", saved))
        assertNull(Dial.stationError("X", "http://b.example/live", saved))
    }

    @Test fun retryBacksOffToAMinute() {
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L), (0..6).map { Dial.retryDelayMs(it) })
        assertEquals(3_000L, Dial.retryDelayMs(-1))
        assertEquals(60_000L, Dial.retryDelayMs(1_000))
    }

    @Test fun searchMetaSkipsWhatIsMissing() {
        assertEquals("GERMANY / 128 KBPS / JAZZ,SMOOTH", Dial.meta(SearchHit("X", "https://x", "Germany", 128, "jazz,smooth")))
        assertEquals("64 KBPS", Dial.meta(SearchHit("X", "https://x", "", 64, " ")))
        assertEquals("", Dial.meta(SearchHit("X", "https://x", "", 0, "")))
    }
}
