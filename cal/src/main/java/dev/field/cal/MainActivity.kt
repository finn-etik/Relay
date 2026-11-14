package dev.field.cal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.field.ui.BlinkingLed
import dev.field.ui.EmptyCard
import dev.field.ui.FieldColors
import dev.field.ui.FieldInput
import dev.field.ui.FieldKey
import dev.field.ui.FieldScreen
import dev.field.ui.FieldText
import dev.field.ui.FieldTheme
import dev.field.ui.FieldToggleRow
import dev.field.ui.Fmt
import dev.field.ui.KeyVariant
import dev.field.ui.LedMode
import dev.field.ui.PaperCard
import dev.field.ui.RegisterPanel
import dev.field.ui.SectionHeader
import dev.field.ui.SkinLine
import dev.field.ui.StatusHeader
import dev.field.ui.fieldEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * CAL: calendar in the Field Design Language (CAL month grid merged with the
 * Calendula day timeline). Works on the phone's real calendars.
 * Skin: signal green. Extensions: input row, timeline.
 */
class MainActivity : ComponentActivity() {

    private var granted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fieldEdgeToEdge()
        setContent {
            FieldTheme(FieldColors.SignalGreen) {
                CalApp(granted = granted, onGranted = { granted = hasAccess() })
            }
        }
    }

    // Re-checked on every resume: the user may grant access in Settings.
    override fun onResume() {
        super.onResume()
        granted = hasAccess()
    }

    private fun hasAccess(): Boolean = PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}

private enum class CalView { DAY, MONTH }

/** CAL's own settings: hidden calendars and where new entries go. */
private class CalPrefs(context: Context) {
    private val p = context.getSharedPreferences("cal", Context.MODE_PRIVATE)
    var hidden: Set<Long>
        get() = p.getStringSet("hidden", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = p.edit().putStringSet("hidden", v.map { it.toString() }.toSet()).apply()
    var target: Long
        get() = p.getLong("target", -1L)
        set(v) = p.edit().putLong("target", v).apply()
    var hidePast: Boolean
        get() = p.getBoolean("hidePast", false)
        set(v) = p.edit().putBoolean("hidePast", v).apply()
}

private class Loaded(val calendars: List<PhoneCalendar>, val byDate: Map<LocalDate, List<Entry>>)

@Composable
private fun CalApp(granted: Boolean, onGranted: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { CalendarRepo(context.applicationContext) }
    val prefs = remember { CalPrefs(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    // ---- clock: minute resolution for the screen (the footer ticks on its own) ----
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(60_000 - nowMs % 60_000)
        }
    }
    val nowTime = remember(nowMs) { Instant.ofEpochMilli(nowMs).atZone(zone) }
    val today = nowTime.toLocalDate()
    val nowMin = nowTime.hour * 60 + nowTime.minute

    // ---- UI state (survives rotation) ----
    var selectedDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedDay)
    var view by rememberSaveable { mutableStateOf(CalView.DAY) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var hidePast by remember { mutableStateOf(prefs.hidePast) }
    var hidden by remember { mutableStateOf(prefs.hidden) }
    var quick by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<Draft?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // ---- data: reload on range change or any calendar change ----
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(granted) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                version++
            }
        }
        if (granted) context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    val month = YearMonth.from(selected)
    var data by remember { mutableStateOf<Loaded?>(null) }
    LaunchedEffect(granted, month, hidden, version, today) {
        if (!granted) return@LaunchedEffect
        data = withContext(Dispatchers.IO) {
            try {
                val grid = CalLogic.monthGrid(month)
                val entries = repo.entries(grid.first(), grid.last().plusDays(1), hidden, zone)
                Loaded(repo.calendars(), entries.groupBy { it.date })
            } catch (_: SecurityException) {
                null
            }
        }
    }

    val permissionAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onGranted() }

    fun flash(text: String) {
        message = text
        scope.launch {
            delay(2500)
            if (message == text) message = null
        }
    }

    val byDate = data?.byDate ?: emptyMap()
    val calendars = data?.calendars ?: emptyList()
    val writable = calendars.filter { it.writable }
    val dayEntries = byDate[selected].orEmpty()
    val counts = remember(byDate) { byDate.mapValues { it.value.size } }
    val liveEntries = byDate[today].orEmpty().filter { CalLogic.status(it, nowMs) == EntryStatus.LIVE }
    val isLive = liveEntries.isNotEmpty()
    val visible = dayEntries.filter { !(hidePast && CalLogic.status(it, nowMs) == EntryStatus.PAST) }
    val selectedEntry = dayEntries.firstOrNull { it.key == selectedKey }

    fun pick(date: LocalDate) {
        selectedDay = date.toEpochDay()
        selectedKey = null
        confirmDelete = false
    }

    fun defaultTarget(): Long? =
        writable.firstOrNull { it.id == prefs.target }?.id
            ?: writable.firstOrNull { it.primary }?.id
            ?: writable.firstOrNull()?.id

    /** Runs a write; creates the local FIELD calendar first if there is nowhere to write. */
    fun write(done: String, block: (calendarId: Long?) -> Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    var target = defaultTarget()
                    if (target == null && writable.isEmpty()) target = repo.createLocalCalendar(zone)
                    block(target)
                } catch (_: Exception) {
                    false
                }
            }
            flash(if (ok) done else "WRITE FAILED")
            version++
        }
    }

    fun openEditor(draft: Draft, eventId: Long?) {
        editor = draft
        editingId = eventId
        selectedKey = null
    }

    FieldScreen {
        StatusHeader(
            title = "CAL",
            led = when {
                isLive -> LedMode.Slow
                selected == today -> LedMode.Solid
                else -> LedMode.Off
            },
            status = when {
                isLive -> "LIVE"
                selected == today -> "TODAY"
                else -> "BROWSE"
            },
            left = CalLogic.stamp(selected) + " / WK " + CalLogic.isoWeek(selected) + " / " +
                dayEntries.size + if (dayEntries.size == 1) " ENTRY" else " ENTRIES",
            right = if (isLive) "${liveEntries.size} LIVE" else "",
        )

        if (!granted) {
            EmptyCard("CALENDAR ACCESS", "CAL READS AND WRITES THE CALENDARS ON THIS PHONE. NOTHING LEAVES THE DEVICE EXCEPT THROUGH YOUR OWN CALENDAR ACCOUNTS.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldKey("GRANT", { permissionAsk.launch(MainActivity.PERMISSIONS) }, Modifier.weight(1f), size = 12.sp, variant = KeyVariant.Accent)
                FieldKey("SETTINGS", {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                }, Modifier.weight(1f), size = 12.sp)
            }
            SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL GREEN / EXT: INPUT ROW, TIMELINE")
            return@FieldScreen
        }

        // ---- register panel ----
        RegisterPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BlinkingLed(if (selected == today) (if (isLive) LedMode.Slow else LedMode.Solid) else LedMode.Off)
                    FieldText(CalLogic.MONTHS[selected.monthValue - 1] + " " + Fmt.pad2(selected.dayOfMonth), 16.sp, color = FieldColors.Paper)
                }
                FieldText(if (dayEntries.isEmpty()) "NO ENTRIES" else Fmt.pad2(dayEntries.size) + " ENTRIES", 10.sp, color = FieldColors.Dim, tracking = 1.sp)
            }
            val next = dayEntries.firstOrNull { !it.allDay && CalLogic.status(it, nowMs) == EntryStatus.UPCOMING }
            val line = when {
                selected == today && isLive -> liveEntries.first().let { "ON NOW / " + it.title.uppercase() + " / ENDS " + Fmt.hhmm(it.endMin) }
                selected == today && next != null -> "NEXT " + Fmt.hhmm(next.startMin) + " / " + next.title.uppercase()
                dayEntries.isEmpty() -> "DAY IS CLEAR"
                else -> CalLogic.WEEKDAYS_3[selected.dayOfWeek.value - 1] + " / " + dayEntries.count { it.allDay } + " ALL DAY"
            }
            FieldText(line, 10.sp, Modifier.padding(top = 12.dp), color = if (isLive && selected == today) FieldColors.SignalGreen else FieldColors.Dim, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val monthCount = byDate.filterKeys { YearMonth.from(it) == month }.values.sumOf { it.size }
            FieldText(
                "THIS MONTH " + Fmt.pad2(monthCount) + " / CALENDARS " + Fmt.pad2(calendars.size - hidden.count { id -> calendars.any { it.id == id } }),
                10.sp, Modifier.padding(top = 12.dp), color = FieldColors.SignalGreen, tracking = 1.sp,
            )
        }

        message?.let { FieldText("! $it", 10.sp, color = FieldColors.SignalGreen, tracking = 1.sp) }

        // ---- navigation ----
        SectionHeader(if (view == CalView.DAY) "DAY" else "MONTH " + CalLogic.MONTHS[month.monthValue - 1] + " " + month.year)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val step: (Long) -> Unit = { n -> pick(if (view == CalView.DAY) selected.plusDays(n) else selected.plusMonths(n)) }
            FieldKey("<", { step(-1) }, Modifier.weight(1f), height = 40.dp, size = 14.sp)
            FieldKey("TODAY", { pick(today) }, Modifier.weight(2f), height = 40.dp, size = 12.sp, active = selected == today)
            FieldKey(">", { step(1) }, Modifier.weight(1f), height = 40.dp, size = 14.sp)
            FieldKey("DAY", { view = CalView.DAY }, Modifier.weight(1.4f), height = 40.dp, size = 12.sp, active = view == CalView.DAY)
            FieldKey("MON", { view = CalView.MONTH }, Modifier.weight(1.4f), height = 40.dp, size = 12.sp, active = view == CalView.MONTH)
        }

        if (view == CalView.MONTH) {
            MonthGrid(selected, today, counts, onPick = { pick(it) })
            SectionHeader("AGENDA / " + CalLogic.stamp(selected))
            if (dayEntries.isEmpty()) EmptyCard("NO ENTRIES", "DAY IS CLEAR. ADD ONE BELOW.")
            else AgendaList(dayEntries, nowMs, selectedKey) { selectedKey = if (selectedKey == it.key) null else it.key }
        } else {
            WeekStrip(selected, today, counts, onPick = { pick(it) })
            FieldToggleRow("HIDE PAST", "hide entries that already ended", hidePast) {
                hidePast = !hidePast
                prefs.hidePast = hidePast
            }
        }

        // ---- selected entry ----
        selectedEntry?.let { e ->
            RegisterPanel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val st = CalLogic.status(e, nowMs)
                    if (st == EntryStatus.LIVE) BlinkingLed(LedMode.Slow, size = 10.dp)
                    FieldText(e.title.uppercase(), 16.sp, color = FieldColors.Paper, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                FieldText(
                    CalLogic.stamp(e.date) + (if (e.location.isNotBlank()) " / " + e.location.uppercase() else ""),
                    10.sp, Modifier.padding(top = 6.dp), color = FieldColors.Dim, tracking = 1.sp,
                )
                FieldText(
                    when {
                        e.allDay -> "ALL DAY"
                        CalLogic.status(e, nowMs) == EntryStatus.LIVE -> "ON NOW / ENDS " + Fmt.hhmm(e.endMin)
                        else -> Fmt.hhmm(e.startMin) + "/" + Fmt.hhmm(e.endMin) + " / " + e.durationMin + "M"
                    },
                    10.sp, Modifier.padding(top = 10.dp),
                    color = if (CalLogic.status(e, nowMs) == EntryStatus.LIVE) FieldColors.SignalGreen else FieldColors.Paper,
                    tracking = 1.sp,
                )
                FieldText(
                    e.calendarName.uppercase() + when {
                        !e.writable -> " / READ ONLY"
                        e.recurring -> " / REPEATS: EDIT IN SOURCE APP"
                        else -> ""
                    },
                    9.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim,
                )
                val editable = e.writable && !e.recurring
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldKey("EDIT", { openEditor(repo.draftOf(e, zone), e.eventId) }, Modifier.weight(1f), height = 40.dp, size = 12.sp, enabled = editable)
                    FieldKey(if (confirmDelete) "CONFIRM" else "DEL", {
                        if (!confirmDelete) {
                            confirmDelete = true
                            scope.launch { delay(3000); confirmDelete = false }
                        } else {
                            confirmDelete = false
                            selectedKey = null
                            write("DELETED") { _ -> repo.delete(e.eventId) }
                        }
                    }, Modifier.weight(1f), height = 40.dp, size = 12.sp, variant = KeyVariant.Ink, enabled = editable)
                    FieldKey("CLOSE", { selectedKey = null }, Modifier.weight(1f), height = 40.dp, size = 12.sp, variant = KeyVariant.Accent)
                }
            }
        }

        // ---- day timeline ----
        if (view == CalView.DAY) {
            AllDayRows(visible.filter { it.allDay }, selectedKey) { selectedKey = if (selectedKey == it.key) null else it.key }
            val timed = visible.filter { !it.allDay }
            if (timed.isEmpty() && selected != today) {
                EmptyCard("NO TIMED ENTRIES", "DAY IS CLEAR / TAP ARROW KEY TO SWITCH DAY.")
            } else {
                Timeline(
                    date = selected,
                    entries = timed,
                    nowMs = nowMs,
                    nowMin = if (selected == today) nowMin else null,
                    selectedKey = selectedKey,
                    onSelect = { selectedKey = if (selectedKey == it.key) null else it.key },
                )
            }
        }

        // ---- add ----
        SectionHeader("ADD")
        val editing = editor
        if (editing == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val addQuick = {
                    val (start, title) = CalLogic.parseQuickAdd(quick)
                    if (title.isNotBlank()) {
                        val draft = Draft(title, "", selected, start ?: 0, 60, start == null, -1)
                        quick = ""
                        write("ADDED") { target -> target != null && repo.insert(draft.copy(calendarId = target)) != null }
                    }
                }
                FieldInput(quick, { quick = it }, "14:30 DENTIST", Modifier.weight(1f), onDone = addQuick)
                FieldKey("ADD", addQuick, Modifier.width(64.dp), size = 12.sp, variant = KeyVariant.Accent)
                FieldKey("+", {
                    val start = if (selected == today) ((nowMin / 60) + 1).coerceAtMost(23) * 60 else 9 * 60
                    openEditor(Draft("", "", selected, start, 60, false, defaultTarget() ?: -1), null)
                }, Modifier.width(52.dp), size = 15.sp)
            }
            FieldText("TYPE A TIME FIRST FOR A TIMED ENTRY, OR PRESS + FOR THE FULL EDITOR.", 9.sp, color = FieldColors.Dim)
        } else {
            Editor(
                draft = editing,
                isNew = editingId == null,
                writable = writable,
                onChange = { editor = it },
                onCancel = { editor = null; editingId = null },
                onSave = {
                    val d = editing
                    val id = editingId
                    editor = null
                    editingId = null
                    if (d.calendarId >= 0) prefs.target = d.calendarId
                    if (id == null) {
                        write("ADDED") { target ->
                            val cal = if (d.calendarId >= 0) d.calendarId else target
                            cal != null && repo.insert(d.copy(calendarId = cal)) != null
                        }
                    } else {
                        write("SAVED") { _ -> repo.update(id, d) }
                    }
                },
            )
        }

        // ---- calendars ----
        SectionHeader("CALENDARS")
        if (calendars.isEmpty()) {
            EmptyCard("NO CALENDARS", "THIS PHONE HAS NO CALENDAR ACCOUNT. THE FIRST ENTRY YOU ADD CREATES A LOCAL \"FIELD\" CALENDAR ON THE DEVICE.")
        } else {
            calendars.forEach { c ->
                FieldToggleRow(
                    c.name.uppercase(),
                    c.account.lowercase() + (if (!c.writable) " / read only" else "") + (if (c.id == defaultTarget()) " / new entries go here" else ""),
                    c.id !in hidden,
                ) {
                    hidden = if (c.id in hidden) hidden - c.id else hidden + c.id
                    prefs.hidden = hidden
                }
            }
        }

        Footer(nowMsAtMinute = nowMs)
        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL GREEN / EXT: INPUT ROW, TIMELINE")
    }
}

@Composable
private fun Editor(
    draft: Draft,
    isNew: Boolean,
    writable: List<PhoneCalendar>,
    onChange: (Draft) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    PaperCard(borderColor = FieldColors.SignalGreen, borderWidth = 2.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldText(if (isNew) "NEW ENTRY" else "EDIT ENTRY", 12.sp, tracking = 1.sp)
            FieldInput(draft.title, { onChange(draft.copy(title = it)) }, "TITLE", Modifier.fillMaxWidth())
            FieldInput(draft.location, { onChange(draft.copy(location = it)) }, "LOCATION", Modifier.fillMaxWidth())

            Stepper(CalLogic.stamp(draft.date), listOf("-1D" to -1, "+1D" to 1)) { onChange(draft.copy(date = draft.date.plusDays(it.toLong()))) }
            FieldToggleRow("ALL DAY", "no start time", draft.allDay) { onChange(draft.copy(allDay = !draft.allDay)) }
            if (!draft.allDay) {
                Stepper("START " + Fmt.hhmm(draft.startMin), listOf("-1H" to -60, "-15" to -15, "+15" to 15, "+1H" to 60)) {
                    onChange(draft.copy(startMin = (draft.startMin + it).coerceIn(0, 1439)))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60, 90, 120).forEach { m ->
                        FieldKey(m.toString(), { onChange(draft.copy(durationMin = m)) }, Modifier.weight(1f), height = 36.dp, size = 11.sp, active = draft.durationMin == m)
                    }
                }
                FieldText("DURATION " + draft.durationMin + "M / ENDS " + Fmt.hhmm(draft.startMin + draft.durationMin), 9.sp, color = FieldColors.Dim)
            }
            if (isNew && writable.size > 1) {
                val current = writable.firstOrNull { it.id == draft.calendarId } ?: writable.first()
                FieldKey("TO: " + current.name.uppercase() + " >", {
                    val i = writable.indexOf(current)
                    onChange(draft.copy(calendarId = writable[(i + 1) % writable.size].id))
                }, Modifier.fillMaxWidth(), height = 40.dp, size = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldKey("CANCEL", onCancel, Modifier.weight(1f), height = 44.dp, size = 12.sp)
                FieldKey("SAVE", onSave, Modifier.weight(1f), height = 44.dp, size = 12.sp, variant = KeyVariant.Accent, enabled = draft.title.isNotBlank())
            }
        }
    }
}

@Composable
private fun Stepper(label: String, steps: List<Pair<String, Int>>, onStep: (Int) -> Unit) {
    val half = steps.size / 2
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.take(half).forEach { (l, v) -> FieldKey(l, { onStep(v) }, Modifier.weight(1f), height = 36.dp, size = 11.sp) }
        Box(Modifier.weight(2.4f), contentAlignment = Alignment.Center) { FieldText(label, 11.sp, tracking = 1.sp, maxLines = 1) }
        steps.drop(half).forEach { (l, v) -> FieldKey(l, { onStep(v) }, Modifier.weight(1f), height = 36.dp, size = 11.sp) }
    }
}

/** Counter line: the only thing that redraws every second. */
@Composable
private fun Footer(nowMsAtMinute: Long) {
    var time by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            time = LocalTime.now()
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    val today = remember(nowMsAtMinute) { LocalDate.now() }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        FieldText("LOCAL " + Fmt.pad2(time.hour) + ":" + Fmt.pad2(time.minute) + ":" + Fmt.pad2(time.second), 10.sp, color = FieldColors.SignalGreen, tracking = 1.sp)
        FieldText("DAY " + today.dayOfYear + "/" + today.lengthOfYear(), 10.sp, color = FieldColors.SignalGreen, tracking = 1.sp)
    }
}
