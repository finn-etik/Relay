package dev.field.dial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DialTest {

    private val a = Station(1, "A", "https://a.example/stream")
    private val b = Station(2, "B", "https://b.example/stream")
    private val s = 1000L

    @Test fun stationValidation() {
        val saved = listOf(a)
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

    @Test fun airtimeIsDerivedFromTheWallClock() {
        var d = DialState(stations = listOf(a, b)).tune(a, 0)
        assertEquals(Phase.CONNECT, d.phase)
        assertEquals(0L, d.airMs(5 * s)) // connecting is not airtime
        d = d.onAir(2 * s)
        assertEquals(Phase.ON, d.phase)
        assertEquals(10 * s, d.airMs(12 * s)) // e.g. the screen was off for 10 s
        assertEquals(10 * s, d.totalMs(12 * s))
    }

    @Test fun reconnectKeepsAirtimeAndCountsAttempts() {
        var d = DialState().tune(a, 0).onAir(0).dropped(30 * s)
        assertEquals(Phase.ERR, d.phase)
        assertEquals(30 * s, d.airMs(99 * s)) // frozen while down
        d = d.reconnect(33 * s)
        assertEquals(1, d.attempts)
        d = d.dropped(34 * s).reconnect(40 * s)
        assertEquals(2, d.attempts)
        d = d.onAir(41 * s)
        assertEquals(0, d.attempts)
        assertEquals(40 * s, d.airMs(51 * s)) // 30 s before the drop + 10 s after
        assertEquals(a, d.playing)
    }

    @Test fun stopBanksTheTotalAndClearsTheSession() {
        val d = DialState(totalBankedMs = 100 * s).tune(a, 0).onAir(0).sleepIn(0, 15).stop(20 * s)
        assertEquals(Phase.OFF, d.phase)
        assertNull(d.playing)
        assertEquals(0L, d.airMs(50 * s))
        assertEquals(120 * s, d.totalMs(50 * s))
        assertEquals(0L, d.sleepAt)
    }

    @Test fun retuneStartsAFreshSessionButKeepsTheTotal() {
        val d = DialState().tune(a, 0).onAir(0).tune(b, 10 * s).onAir(11 * s)
        assertEquals(b, d.playing)
        assertEquals(b.id, d.selId)
        assertEquals(5 * s, d.airMs(16 * s))
        assertEquals(15 * s, d.totalMs(16 * s))
    }

    @Test fun lateCallbacksDoNotChangeAStoppedPlayer() {
        val off = DialState()
        assertSame(off, off.onAir(5 * s))   // "prepared" after STOP
        assertSame(off, off.dropped(5 * s)) // "error" after STOP
        assertSame(off, off.reconnect(5 * s))
    }

    @Test fun stationsAddDeleteMove() {
        var d = DialState(stations = listOf(a, b), selId = 2).add(" c ", " https://c.example ")
        assertEquals(Station(3, "C", "https://c.example"), d.stations.last())
        d = d.move(3, -1)
        assertEquals(listOf(1, 3, 2), d.stations.map { it.id })
        assertSame(d, d.move(1, -1)) // already first
        assertSame(d, d.move(2, 1))  // already last
        d = d.delete(2)
        assertEquals(listOf(1, 3), d.stations.map { it.id })
        assertEquals(-1, d.selId)
        assertEquals(1, DialState().add("x", "http://x").stations.single().id)
    }

    @Test fun volumeAndSleep() {
        assertEquals(10, DialState(vol = 10).volume(1).vol)
        assertEquals(0, DialState(vol = 0).volume(-1).vol)
        assertEquals(false, DialState(vol = 5, muted = true).volume(1).muted)
        assertEquals(0f, DialState(muted = true).volumeFraction, 0f)
        val d = DialState().sleepIn(0, 15)
        assertEquals(14 * 60 * s, d.sleepRemainingMs(60 * s))
        assertEquals(0L, d.sleepRemainingMs(16 * 60 * s))
        assertEquals(0L, d.sleepIn(0, 0).sleepAt)
    }
}
