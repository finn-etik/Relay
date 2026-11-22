package dev.field.cal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** One calendar event occurrence, clipped to a single day for display. */
data class Entry(
    val eventId: Long,
    /** Occurrence start (ms); with eventId it identifies a recurring instance. */
    val instanceBegin: Long,
    val instanceEnd: Long,
    val title: String,
    val location: String,
    val date: LocalDate,
    /** Minutes after midnight on [date]; 0..1440. */
    val startMin: Int,
    val endMin: Int,
    val allDay: Boolean,
    val calendarId: Long,
    val calendarName: String,
    val recurring: Boolean,
    val writable: Boolean,
) {
    val key: String get() = "$eventId@$instanceBegin@$date"
    val durationMin: Int get() = endMin - startMin
}

enum class EntryStatus { PAST, LIVE, UPCOMING }

/** A day-slice of an occurrence (multi-day events produce several). */
data class DaySlice(val date: LocalDate, val startMin: Int, val endMin: Int)

object CalLogic {

    val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    val WEEKDAYS_3 = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    fun isoWeek(date: LocalDate): Int = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** 6 x 7 Monday-first grid covering [month]. */
    fun monthGrid(month: YearMonth): List<LocalDate> {
        val start = weekStart(month.atDay(1))
        return (0 until 42).map { start.plusDays(it.toLong()) }
    }

    /** "SAT 14 NOV 2026" */
    fun stamp(date: LocalDate): String =
        WEEKDAYS_3[date.dayOfWeek.value - 1] + " " + date.dayOfMonth.toString().padStart(2, '0') +
            " " + MONTHS[date.monthValue - 1] + " " + date.year

    /**
     * Splits an occurrence into per-day slices.
     *
     * All-day events are stored by Android as UTC midnights (DTEND exclusive),
     * so their days must be read in UTC, not the local zone; reading them in
     * a zone west of UTC would shift every all-day event to the previous day.
     */
    fun slices(beginMs: Long, endMs: Long, allDay: Boolean, zone: ZoneId): List<DaySlice> {
        if (allDay) {
            val first = Instant.ofEpochMilli(beginMs).atZone(ZoneOffset.UTC).toLocalDate()
            val endDate = Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate()
            val lastExclusive = if (endDate.isAfter(first)) endDate else first.plusDays(1)
            return generateSequence(first) { it.plusDays(1) }
                .takeWhile { it.isBefore(lastExclusive) }
                .map { DaySlice(it, 0, 1440) }
                .toList()
        }
        val begin = Instant.ofEpochMilli(beginMs).atZone(zone)
        val end = Instant.ofEpochMilli(maxOf(endMs, beginMs)).atZone(zone)
        val out = ArrayList<DaySlice>()
        var day = begin.toLocalDate()
        val lastDay = if (end.toLocalTime() == LocalTime.MIDNIGHT && end.isAfter(begin)) {
            end.toLocalDate().minusDays(1) // ends exactly at midnight: not on the next day
        } else {
            end.toLocalDate()
        }
        while (!day.isAfter(lastDay)) {
            val dayStart = day.atStartOfDay(zone)
            val s = if (day == begin.toLocalDate()) ChronoUnit.MINUTES.between(dayStart, begin).toInt() else 0
            val e = if (day == end.toLocalDate()) ChronoUnit.MINUTES.between(dayStart, end).toInt() else 1440
            out += DaySlice(day, s.coerceIn(0, 1440), e.coerceIn(0, 1440))
            day = day.plusDays(1)
        }
        return out
    }

    /**
     * All-day entries are stored as UTC midnights, so comparing their instants
     * with the clock made them "live" from 01:00 to 01:00 in UTC+1 (and at
     * other wrong hours elsewhere). They are judged by calendar day instead,
     * and are never LIVE: a birthday should not blink all day.
     */
    fun status(entry: Entry, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): EntryStatus = when {
        entry.allDay ->
            if (entry.date.isBefore(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate())) EntryStatus.PAST
            else EntryStatus.UPCOMING
        nowMs < entry.instanceBegin -> EntryStatus.UPCOMING
        nowMs < entry.instanceEnd -> EntryStatus.LIVE
        else -> EntryStatus.PAST
    }

    /**
     * Side-by-side lanes for overlapping timed entries. Returns, per entry
     * key, (lane index, lane count of its overlap cluster).
     */
    fun lanes(entries: List<Entry>, minHeightMin: Int = 40): Map<String, Pair<Int, Int>> {
        val sorted = entries.filter { !it.allDay }.sortedWith(compareBy({ it.startMin }, { -it.endMin }))
        val result = HashMap<String, Pair<Int, Int>>()
        var cluster = ArrayList<Pair<Entry, Int>>()
        var laneEnds = ArrayList<Int>()
        var clusterEnd = -1
        fun flush() {
            val count = laneEnds.size
            cluster.forEach { (e, lane) -> result[e.key] = lane to count }
            cluster = ArrayList()
            laneEnds = ArrayList()
        }
        for (e in sorted) {
            // Short entries are drawn at least minHeightMin tall; lay them out that way too.
            val end = maxOf(e.endMin, e.startMin + minHeightMin)
            if (e.startMin >= clusterEnd && cluster.isNotEmpty()) flush()
            var lane = laneEnds.indexOfFirst { it <= e.startMin }
            if (lane < 0) {
                lane = laneEnds.size
                laneEnds.add(end)
            } else {
                laneEnds[lane] = end
            }
            cluster.add(e to lane)
            clusterEnd = if (cluster.size == 1) end else maxOf(clusterEnd, end)
        }
        if (cluster.isNotEmpty()) flush()
        return result
    }

    /** First day and day count of an all-day occurrence (UTC midnights, end exclusive). */
    fun allDaySpan(beginMs: Long, endMs: Long): Pair<LocalDate, Int> {
        val first = Instant.ofEpochMilli(beginMs).atZone(ZoneOffset.UTC).toLocalDate()
        val days = ((endMs - beginMs) / 86_400_000L).toInt().coerceAtLeast(1)
        return first to days
    }

    /** Quick add result: [startMin] null = all-day. */
    data class QuickAdd(val startMin: Int?, val durationMin: Int, val title: String)

    private val quick = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?(?:\\s*[-\u2013]\\s*(\\d{1,2})(?:[:.](\\d{2}))?)?\\s+(.+)$")

    /**
     * Quick add: "14:30 DENTIST" is a 60-minute entry at 14:30; "9 RUN" and
     * "9.15 RUN" also work. A range sets the length: "14:30-16:00 WORKSHOP".
     * A bare end hour that is not after the start is read as afternoon
     * ("9-5 OFFICE" ends 17:00), otherwise as the next day ("22-6 SHIFT").
     * Anything else is an all-day entry.
     */
    fun parseQuick(text: String): QuickAdd {
        val t = text.trim()
        val allDay = QuickAdd(null, 60, t)
        val m = quick.find(t) ?: return allDay
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        if (h > 23 || min > 59) return allDay
        val start = h * 60 + min
        val title = m.groupValues[5].trim()
        if (m.groupValues[3].isEmpty()) return QuickAdd(start, 60, title)
        val endH = m.groupValues[3].toInt()
        val endMin = m.groupValues[4].ifEmpty { "0" }.toInt()
        if (endH > 24 || endMin > 59 || (endH == 24 && endMin > 0)) return allDay
        var end = endH * 60 + endMin
        if (end <= start) end += if (endH < 12 && end + 720 > start) 720 else 1440
        return QuickAdd(start, end - start, title)
    }

    /** (start minute or null for all-day, title). */
    fun parseQuickAdd(text: String): Pair<Int?, String> = parseQuick(text).let { it.startMin to it.title }
}
