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
}
