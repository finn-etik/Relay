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
}
