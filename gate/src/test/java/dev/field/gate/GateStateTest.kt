package dev.field.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GateStateTest {

    private val min = 60_000L

    @Test fun countdownIsDerivedFromWallClock() {
        val s = GateState(presetMin = 25).start(now = 0)
        assertEquals(Phase.ON, s.phase)
        assertEquals(25 * min, s.remainingMs(0))
        assertEquals(15 * min, s.remainingMs(10 * min)) // e.g. app was closed for 10 min
        assertEquals(10 * min, s.elapsedMs(10 * min))
    }

    @Test fun holdFreezesAndResumeShiftsTheEnd() {
        val held = GateState(presetMin = 10).start(0).hold(4 * min)
        assertEquals(Phase.HOLD, held.phase)
        assertEquals(6 * min, held.remainingMs(100 * min)) // frozen
        val resumed = held.start(50 * min)
        assertEquals(56 * min, resumed.endAt)
    }

    @Test fun completesOnlyWhenTimeIsUpAndLogs() {
        val on = GateState(presetMin = 5).start(0)
        assertSame(on, on.complete(4 * min)) // too early: no change
        val done = on.complete(5 * min)
        assertEquals(Phase.DONE, done.phase)
        assertEquals(listOf(LogEntry(5, 5 * min)), done.log)
        assertSame(done, done.complete(6 * min)) // idempotent
    }

    @Test fun doneSettlesToStandbyAfterFlash() {
        val done = GateState(presetMin = 5).start(0).complete(5 * min)
        assertEquals(Phase.DONE, done.settle(5 * min + 1000).phase)
        assertEquals(Phase.STANDBY, done.settle(5 * min + 3000).phase)
    }

    @Test fun logIsCappedNewestFirst() {
        var s = GateState(presetMin = 5)
        repeat(35) { i ->
            val t = i * 10 * min
            s = s.start(t).complete(t + 5 * min).stop()
        }
        assertEquals(30, s.log.size)
        assertEquals(34 * 10 * min + 5 * min, s.log.first().endedAt)
    }

    @Test fun presetLockedWhileRunning() {
        val on = GateState(presetMin = 25).start(0)
        assertEquals(25, on.selectPreset(5).presetMin)
        assertEquals(5, GateState().selectPreset(5).presetMin)
    }

    @Test fun presetAdjustsWithinLimits() {
        assertEquals(27, GateState(presetMin = 25).adjustPreset(1).adjustPreset(1).presetMin)
        assertEquals(1, GateState(presetMin = 5).adjustPreset(-5).adjustPreset(-5).presetMin)
        assertEquals(180, GateState(presetMin = 178).adjustPreset(5).presetMin)
        assertEquals(25, GateState(presetMin = 25).start(0).adjustPreset(5).presetMin) // locked while running
    }

    @Test fun minutesAreSummedPerDay() {
        val day = 24 * 60 * min
        var s = GateState(presetMin = 25).start(0).complete(25 * min).stop()            // day 0
        s = s.selectPreset(10).start(day).complete(day + 10 * min).stop()               // day 1
        s = s.selectPreset(50).start(day + 60 * min).complete(day + 110 * min).stop()   // day 1
        assertEquals(25, s.minutesBetween(0, day))
        assertEquals(60, s.minutesBetween(day, 2 * day))
        assertEquals(0, s.minutesBetween(2 * day, 3 * day))
    }

    @Test fun anotherAppCanBeginASessionButNotRestartOne() {
        val s = GateState(presetMin = 25).begin(0, 45)
        assertEquals(Phase.ON, s.phase)
        assertEquals(45, s.presetMin)
        assertEquals(45 * min, s.remainingMs(0))
        assertEquals(s, s.begin(10 * min, 5))                       // running: joined, not restarted
        val held = s.hold(10 * min)
        assertEquals(held, held.begin(11 * min, 5))                 // on hold: left alone
        assertEquals(180, GateState().begin(0, 999).presetMin)      // clamped to GATE's limits
        assertEquals(1, GateState().begin(0, 0).presetMin)
        val again = s.complete(45 * min).begin(50 * min, 10)        // DONE: a new one may start
        assertEquals(Phase.ON, again.phase)
        assertEquals(10, again.presetMin)
    }
}
