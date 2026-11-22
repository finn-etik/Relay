package dev.field.cal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

class CalLogicTest {

    private val la = ZoneId.of("America/Los_Angeles")
    private val sto = ZoneId.of("Europe/Stockholm")

    private fun utcMidnight(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun allDayEventsUseUtcDates() {
        val d = LocalDate.of(2026, 11, 14)
        // In Los Angeles, UTC midnight is the previous evening: must still be the 14th.
        val s = CalLogic.slices(utcMidnight(d), utcMidnight(d.plusDays(1)), allDay = true, zone = la)
        assertEquals(listOf(DaySlice(d, 0, 1440)), s)
        val three = CalLogic.slices(utcMidnight(d), utcMidnight(d.plusDays(3)), true, sto)
        assertEquals(3, three.size)
    }

    @Test fun timedEventsSplitAcrossMidnight() {
        val start = LocalDate.of(2026, 11, 14).atTime(22, 0).atZone(sto).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 15).atTime(2, 0).atZone(sto).toInstant().toEpochMilli()
        val s = CalLogic.slices(start, end, false, sto)
        assertEquals(
            listOf(DaySlice(LocalDate.of(2026, 11, 14), 1320, 1440), DaySlice(LocalDate.of(2026, 11, 15), 0, 120)),
            s,
        )
    }

    @Test fun endingAtMidnightStaysOnOneDay() {
        val start = LocalDate.of(2026, 11, 14).atTime(23, 0).atZone(sto).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 11, 15).atStartOfDay(sto).toInstant().toEpochMilli()
        assertEquals(listOf(DaySlice(LocalDate.of(2026, 11, 14), 1380, 1440)), CalLogic.slices(start, end, false, sto))
    }

    @Test fun calendarHelpers() {
        assertEquals(46, CalLogic.isoWeek(LocalDate.of(2026, 11, 14)))
        assertEquals(53, CalLogic.isoWeek(LocalDate.of(2026, 12, 31))) // 2026 has 53 ISO weeks
        val grid = CalLogic.monthGrid(YearMonth.of(2026, 11))
        assertEquals(42, grid.size)
        assertEquals(LocalDate.of(2026, 10, 26), grid.first()) // Monday before Nov 1 (a Sunday)
        assertEquals("SAT 14 NOV 2026", CalLogic.stamp(LocalDate.of(2026, 11, 14)))
    }

    private fun e(key: Long, s: Int, end: Int) = Entry(
        key, key, key, "E$key", "", LocalDate.of(2026, 1, 1), s, end, false, 1, "C", false, true,
    )

    @Test fun overlappingEntriesGetLanes() {
        val a = e(1, 600, 660)   // 10:00-11:00
        val b = e(2, 630, 690)   // overlaps a
        val c = e(3, 700, 760)   // after both: new cluster
        val l = CalLogic.lanes(listOf(a, b, c))
        assertEquals(0 to 2, l[a.key])
        assertEquals(1 to 2, l[b.key])
        assertEquals(0 to 1, l[c.key])
    }

    @Test fun shortEntriesCountAtMinimumHeight() {
        val a = e(1, 600, 605)   // 5 min but drawn 40 min tall
        val b = e(2, 620, 680)
        val l = CalLogic.lanes(listOf(a, b))
        assertEquals(2, l[a.key]!!.second)
    }

    @Test fun quickAdd() {
        assertEquals(870 to "DENTIST", CalLogic.parseQuickAdd("14:30 DENTIST"))
        assertEquals(540 to "RUN", CalLogic.parseQuickAdd("9 RUN"))
        assertEquals(555 to "STAND-UP", CalLogic.parseQuickAdd("9.15 STAND-UP"))
        assertEquals(null to "BUY MILK", CalLogic.parseQuickAdd("BUY MILK"))
        assertEquals(null to "25:00 PARTY", CalLogic.parseQuickAdd("25:00 PARTY"))
        assertEquals(null to "2026", CalLogic.parseQuickAdd("2026"))
    }

    @Test fun quickAddRanges() {
        assertEquals(CalLogic.QuickAdd(870, 90, "WORKSHOP"), CalLogic.parseQuick("14:30-16:00 WORKSHOP"))
        assertEquals(CalLogic.QuickAdd(540, 60, "RUN"), CalLogic.parseQuick("9 RUN"))
        assertEquals(CalLogic.QuickAdd(540, 480, "OFFICE"), CalLogic.parseQuick("9-5 OFFICE"))        // 17:00
        assertEquals(CalLogic.QuickAdd(1320, 480, "SHIFT"), CalLogic.parseQuick("22 - 6 SHIFT"))     // next day
        assertEquals(CalLogic.QuickAdd(600, 120, "LUNCH"), CalLogic.parseQuick("10.00\u201312.00 LUNCH"))
        assertEquals(CalLogic.QuickAdd(null, 60, "2026-11 PLAN"), CalLogic.parseQuick("2026-11 PLAN"))
        assertEquals(CalLogic.QuickAdd(null, 60, "9-25 X"), CalLogic.parseQuick("9-25 X"))
    }

    @Test fun allDaySpanKeepsItsLength() {
        val day = 86_400_000L
        assertEquals(LocalDate.of(1970, 1, 3) to 3, CalLogic.allDaySpan(2 * day, 5 * day))
        assertEquals(LocalDate.of(1970, 1, 1) to 1, CalLogic.allDaySpan(0, 0))
    }

    @Test fun allDayStatusFollowsTheLocalDayAndNeverBlinks() {
        val day = 86_400_000L
        val zone = ZoneId.of("Europe/Stockholm")
        fun allDay(date: LocalDate) = Entry(1, date.toEpochDay() * day, (date.toEpochDay() + 1) * day, "X", "", date, 0, 1440, true, 1, "", false, true)
        val today = LocalDate.of(2026, 11, 22)
        val halfPastMidnight = today.atStartOfDay(zone).toInstant().toEpochMilli() + 30 * 60_000L // still "yesterday" in UTC
        assertEquals(EntryStatus.UPCOMING, CalLogic.status(allDay(today), halfPastMidnight, zone))
        assertEquals(EntryStatus.PAST, CalLogic.status(allDay(today.minusDays(1)), halfPastMidnight, zone))
        assertEquals(EntryStatus.UPCOMING, CalLogic.status(allDay(today.plusDays(1)), halfPastMidnight, zone))
    }

    @Test fun dayViewWindow() {
        fun timed(start: Int, end: Int) = Entry(1, 0, 0, "X", "", LocalDate.of(2026, 11, 22), start, end, false, 1, "", false, true)
        assertEquals(8 to 18, CalLogic.window(emptyList(), null))
        assertEquals(6 to 18, CalLogic.window(listOf(timed(6 * 60 + 30, 7 * 60)), null))       // early entry
        assertEquals(8 to 23, CalLogic.window(listOf(timed(21 * 60, 22 * 60 + 15)), null))     // late entry, rounded up
        assertEquals(8 to 24, CalLogic.window(listOf(timed(23 * 60 + 30, 23 * 60 + 45)), null)) // 40-minute block reaches midnight
        assertEquals(8 to 22, CalLogic.window(emptyList(), 20 * 60 + 10))                       // now + 1 h
        assertEquals(0 to 18, CalLogic.window(emptyList(), 30))                                 // just after midnight
        assertEquals(8 to 18, CalLogic.window(listOf(Entry(1, 0, 0, "X", "", LocalDate.of(2026, 11, 22), 0, 1440, true, 1, "", false, true)), null))
    }
}
