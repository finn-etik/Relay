package dev.field.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GoalsTest {

    private val min = 60_000L

    @Test fun recordsNetWordsPerDay() {
        var g = Goals().record(day = 100, delta = 40, now = 0)
        g = g.record(100, -10, 0)
        assertEquals(30, g.today(100))
        assertEquals(0, g.today(101))
        g = g.record(100, -500, 0) // deleting more than was written never goes negative
        assertEquals(0, g.today(100))
        assertSame(g, g.record(100, 0, 0))
    }

    @Test fun streakCountsBackFromTodayOrYesterday() {
        val g = Goals(target = 100, days = mapOf(7L to 100, 8L to 250, 9L to 100, 10L to 20))
        assertEquals(3, g.streak(10))                       // today still open
        assertEquals(4, g.record(10, 80, 0).streak(10))     // today met
        assertEquals(0, g.streak(12))                       // a missed day breaks it
        assertEquals(0, Goals(target = 0).streak(1))
    }

    @Test fun oldDaysAreDropped() {
        val g = Goals(days = mapOf(1L to 5, 601L to 5)).record(1000, 1, 0)
        assertEquals(setOf(601L, 1000L), g.days.keys)
    }

    @Test fun sprintCountsOnlyWhileRunningAndLogsOnce() {
        var g = Goals().startSprint(now = 0, minutes = 10)
        assertEquals(10 * min, g.sprintRemainingMs(0))
        g = g.record(1, 120, 2 * min).record(1, -20, 3 * min)
        assertEquals(100, g.sprint!!.words)
        assertSame(g, g.finishSprint(9 * min)) // too early
        g = g.record(1, 50, 11 * min)          // after the bell: counts for the day only
        assertEquals(100, g.sprint!!.words)
        assertEquals(150, g.today(1))
        val done = g.finishSprint(11 * min)
        assertNull(done.sprint)
        assertEquals(listOf(SprintLog(10, 100, 10 * min)), done.log)
        assertSame(done, done.finishSprint(12 * min))
        assertEquals(0L, done.sprintRemainingMs(12 * min))
    }

    @Test fun cancelLeavesNoLog() {
        val g = Goals().startSprint(0, 5).cancelSprint()
        assertNull(g.sprint)
        assertEquals(emptyList<SprintLog>(), g.log)
    }

    @Test fun sprintJoinsAGateSessionAndIsLoggedAtItsEnd() {
        var g = Goals().followFocus(now = 0, focusRemainingMs = 25 * min, focusMinutes = 25)
        assertEquals(Sprint(25, 25 * min, 0, external = true), g.sprint)
        assertSame(g, g.followFocus(1000, 25 * min - 1000, 25))   // same end: nothing to do
        g = g.record(1, 300, 10 * min)
        val done = g.followFocus(25 * min + 500, null, 0)          // GATE finished
        assertNull(done.sprint)
        assertEquals(listOf(SprintLog(25, 300, 25 * min)), done.log)
    }

    @Test fun sprintFollowsHoldAndAnEarlyStop() {
        var g = Goals().followFocus(0, 10 * min, 10)
        // GATE on hold for a minute: 9 min still left at t = 2 min, so the end moved to 11 min.
        g = g.followFocus(2 * min, 9 * min, 10)
        assertEquals(11 * min, g.sprint!!.endAt)
        val stopped = g.followFocus(3 * min, null, 0)              // stopped in GATE well before the end
        assertNull(stopped.sprint)
        assertEquals(emptyList<SprintLog>(), stopped.log)
    }

    @Test fun aSprintTimedByPageItselfIsNotTouchedByGate() {
        val own = Goals().startSprint(0, 10)
        assertSame(own, own.followFocus(1 * min, null, 0))
        assertSame(own, own.followFocus(1 * min, 25 * min, 25))
        assertSame(Goals().sprint, Goals().followFocus(0, 500, 25).sprint) // a session about to end is not joined
    }
}
