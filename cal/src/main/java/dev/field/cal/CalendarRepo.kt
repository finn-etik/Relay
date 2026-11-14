package dev.field.cal

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A calendar on the phone (Google, DAVx5, Exchange, local, ...). */
data class PhoneCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val writable: Boolean,
    val primary: Boolean,
)

/** What the editor produces. startMin is ignored for all-day entries. */
data class Draft(
    val title: String,
    val location: String,
    val date: LocalDate,
    val startMin: Int,
    val durationMin: Int,
    val allDay: Boolean,
    val calendarId: Long,
)

/**
 * Reads and writes the phone's calendars through CalendarContract, so CAL
 * shows the same events as every other calendar app and syncs through
 * whatever accounts the phone has. Blocking: call off the main thread.
 */
class CalendarRepo(context: Context) {

    private val cr: ContentResolver = context.contentResolver

    fun calendars(): List<PhoneCalendar> {
        val out = ArrayList<PhoneCalendar>()
        cr.query(
            Calendars.CONTENT_URI,
            arrayOf(
                Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
                Calendars.CALENDAR_ACCESS_LEVEL, Calendars.IS_PRIMARY, Calendars.VISIBLE,
            ),
            null, null, Calendars.CALENDAR_DISPLAY_NAME + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == 0) continue // hidden by the user in their calendar settings
                out += PhoneCalendar(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "CALENDAR",
                    account = c.getString(2) ?: "",
                    writable = c.getInt(3) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                    primary = c.getInt(4) == 1,
                )
            }
        }
        return out
    }

    /**
     * All occurrences overlapping [from]..[toExclusive], split into day
     * slices. Recurring events arrive already expanded by the provider.
     */
    fun entries(from: LocalDate, toExclusive: LocalDate, hidden: Set<Long>, zone: ZoneId): List<Entry> {
        // Widen by a day each way: all-day events sit at UTC midnights.
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = toExclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val out = ArrayList<Entry>()
        cr.query(
            uri,
            arrayOf(
                Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.TITLE,
                Instances.EVENT_LOCATION, Instances.ALL_DAY, Instances.CALENDAR_ID,
                Instances.CALENDAR_DISPLAY_NAME, Instances.RRULE, Instances.RDATE,
                Instances.ORIGINAL_ID, Instances.CALENDAR_ACCESS_LEVEL, Instances.VISIBLE,
            ),
            null, null, Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val calendarId = c.getLong(6)
                if (calendarId in hidden || c.getInt(12) == 0) continue
                val allDay = c.getInt(5) == 1
                val b = c.getLong(1)
                val e = c.getLong(2)
                val recurring = !c.isNull(8) || !c.isNull(9) || !c.isNull(10)
                val writable = c.getInt(11) >= Calendars.CAL_ACCESS_CONTRIBUTOR
                for (slice in CalLogic.slices(b, e, allDay, zone)) {
                    if (slice.date.isBefore(from) || !slice.date.isBefore(toExclusive)) continue
                    out += Entry(
                        eventId = c.getLong(0),
                        instanceBegin = b,
                        instanceEnd = e,
                        title = c.getString(3)?.takeIf { it.isNotBlank() } ?: "(NO TITLE)",
                        location = c.getString(4) ?: "",
                        date = slice.date,
                        startMin = slice.startMin,
                        endMin = slice.endMin,
                        allDay = allDay,
                        calendarId = calendarId,
                        calendarName = c.getString(7) ?: "",
                        recurring = recurring,
                        writable = writable,
                    )
                }
            }
        }
        return out
    }

    fun insert(draft: Draft): Long? {
        val uri = cr.insert(Events.CONTENT_URI, values(draft).apply { put(Events.CALENDAR_ID, draft.calendarId) })
        return uri?.let { ContentUris.parseId(it) }
    }

    fun update(eventId: Long, draft: Draft): Boolean =
        cr.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values(draft), null, null) > 0

    fun delete(eventId: Long): Boolean =
        cr.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0

    /** Reads an event back for editing. */
    fun draftOf(entry: Entry, zone: ZoneId): Draft = Draft(
        title = entry.title.takeIf { it != "(NO TITLE)" } ?: "",
        location = entry.location,
        date = if (entry.allDay) entry.date else Instant.ofEpochMilli(entry.instanceBegin).atZone(zone).toLocalDate(),
        startMin = if (entry.allDay) 9 * 60 else Instant.ofEpochMilli(entry.instanceBegin).atZone(zone).let { it.hour * 60 + it.minute },
        durationMin = if (entry.allDay) 60 else ((entry.instanceEnd - entry.instanceBegin) / 60_000L).toInt().coerceAtLeast(5),
        allDay = entry.allDay,
        calendarId = entry.calendarId,
    )

    /**
     * A phone with no calendar account (e.g. without Google) has nowhere to
     * write. This creates an on-device "FIELD" calendar that never syncs.
     */
    fun createLocalCalendar(zone: ZoneId): Long? {
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, "field")
            put(Calendars.CALENDAR_DISPLAY_NAME, "FIELD")
            put(Calendars.CALENDAR_COLOR, 0xFF00C853.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, zone.id)
        }
        return cr.insert(uri, values)?.let { ContentUris.parseId(it) }
    }

    private fun values(d: Draft): ContentValues = ContentValues().apply {
        put(Events.TITLE, d.title)
        put(Events.EVENT_LOCATION, d.location)
        if (d.allDay) {
            // Android convention: all-day = UTC midnights, end exclusive.
            put(Events.DTSTART, d.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            put(Events.DTEND, d.date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.ALL_DAY, 1)
        } else {
            val zone = ZoneId.systemDefault()
            val start = d.date.atStartOfDay(zone).plusMinutes(d.startMin.toLong())
            put(Events.DTSTART, start.toInstant().toEpochMilli())
            put(Events.DTEND, start.plusMinutes(d.durationMin.toLong()).toInstant().toEpochMilli())
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.ALL_DAY, 0)
        }
    }

    companion object {
        const val LOCAL_ACCOUNT = "Field"
    }
}
