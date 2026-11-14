package dev.field.cal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.field.ui.BlinkingLed
import dev.field.ui.CardShape
import dev.field.ui.FieldColors
import dev.field.ui.FieldRow
import dev.field.ui.FieldText
import dev.field.ui.Fmt
import dev.field.ui.LedMode
import dev.field.ui.LocalAccent
import java.time.LocalDate
import java.time.YearMonth

/** Timeline scale: 1 dp per minute (60 dp per hour), as in Calendula. */
private const val DP_PER_MIN = 1f
private val GUTTER = 46.dp

private fun Modifier.tap(onClick: () -> Unit) = clickable(
    interactionSource = null,
    indication = null,
    role = Role.Button,
    onClick = onClick,
)

// ---------------------------------------------------------------- week strip

@Composable
fun WeekStrip(selected: LocalDate, today: LocalDate, counts: Map<LocalDate, Int>, onPick: (LocalDate) -> Unit) {
    val accent = LocalAccent.current
    val start = CalLogic.weekStart(selected)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until 7) {
            val d = start.plusDays(i.toLong())
            val isSel = d == selected
            val count = counts[d] ?: 0
            Box(
                Modifier
                    .weight(1f)
                    .background(FieldColors.Paper, CardShape)
                    .border(if (isSel) 2.dp else 1.5.dp, if (isSel) accent else FieldColors.Ink, CardShape)
                    .tap { onPick(d) }
                    .padding(vertical = 6.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    FieldText(CalLogic.WEEKDAYS_3[i], 9.sp, color = FieldColors.Dim, tracking = 1.sp)
                    FieldText(Fmt.pad2(d.dayOfMonth), 12.sp, Modifier.padding(top = 2.dp))
                    FieldText(
                        if (count > 0) "$count ENT" else "CLEAR", 9.sp, Modifier.padding(top = 2.dp),
                        color = if (count > 0) FieldColors.Dim else FieldColors.Grid,
                    )
                }
                if (d == today) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(5.dp).background(accent))
            }
        }
    }
}

// ---------------------------------------------------------------- month grid

@Composable
fun MonthGrid(selected: LocalDate, today: LocalDate, counts: Map<LocalDate, Int>, onPick: (LocalDate) -> Unit) {
    val accent = LocalAccent.current
    val month = YearMonth.from(selected)
    val grid = remember(month) { CalLogic.monthGrid(month) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CalLogic.WEEKDAYS.forEach { d ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    FieldText(d, 10.sp, color = FieldColors.Dim, tracking = 1.sp)
                }
            }
        }
        grid.chunked(7).forEach { week ->
            if (week.none { YearMonth.from(it) == month }) return@forEach // skip a 6th week entirely outside
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { date ->
                    val inMonth = YearMonth.from(date) == month
                    val isSel = date == selected
                    val isToday = date == today
                    Box(Modifier.weight(1f).height(40.dp)) {
                        if (inMonth) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(FieldColors.Paper, CardShape)
                                    .border(if (isSel) 2.dp else 1.5.dp, if (isSel || isToday) accent else FieldColors.Ink, CardShape)
                                    .tap { onPick(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                FieldText(Fmt.pad2(date.dayOfMonth), 12.sp, color = if (isToday && !isSel) accent else FieldColors.Ink, tracking = 1.sp)
                                if ((counts[date] ?: 0) > 0) {
                                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).background(accent))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- all-day + timeline

@Composable
fun AllDayRows(entries: List<Entry>, selectedKey: String?, onSelect: (Entry) -> Unit) {
    val accent = LocalAccent.current
    entries.forEach { e ->
        val isSel = e.key == selectedKey
        Row(
            Modifier
                .fillMaxWidth()
                .background(FieldColors.Paper, CardShape)
                .border(if (isSel) 2.dp else 1.5.dp, if (isSel) accent else FieldColors.Ink, CardShape)
                .tap { onSelect(e) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FieldText("ALL DAY", 9.sp, color = FieldColors.Dim, tracking = 1.sp)
            FieldText(e.title.uppercase(), 12.sp, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 24-hour timeline. Scrolls itself to the current time (today) or the
 * first entry (other days) whenever the day changes.
 */
@Composable
fun Timeline(
    date: LocalDate,
    entries: List<Entry>,
    nowMs: Long,
    nowMin: Int?,
    selectedKey: String?,
    onSelect: (Entry) -> Unit,
) {
    val accent = LocalAccent.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val lanes = remember(entries) { CalLogic.lanes(entries) }
    LaunchedEffect(date) {
        val targetMin = nowMin?.minus(120) ?: (entries.minOfOrNull { it.startMin }?.minus(60) ?: 8 * 60)
        scroll.scrollTo(with(density) { (targetMin.coerceAtLeast(0) * DP_PER_MIN).dp.roundToPx() })
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(520.dp)
            .background(FieldColors.Paper, CardShape)
            .border(1.5.dp, FieldColors.Ink, CardShape)
            .verticalScroll(scroll),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height((1440 * DP_PER_MIN).dp)) {
            val trackWidth = maxWidth - GUTTER - 16.dp
            for (h in 0..24) {
                val y = (h * 60 * DP_PER_MIN).dp
                Box(Modifier.offset(x = 8.dp, y = y - 6.dp).width(GUTTER - 20.dp)) {
                    FieldText(Fmt.pad2(h) + ":00", 10.sp, Modifier.fillMaxWidth(), color = FieldColors.Dim, align = TextAlign.End)
                }
                if (h < 24) Box(Modifier.offset(x = GUTTER, y = y).fillMaxWidth().height(1.dp).background(FieldColors.Grid))
            }
            entries.forEach { e ->
                val (lane, count) = lanes[e.key] ?: (0 to 1)
                val laneWidth = trackWidth / count
                EntryBlock(
                    entry = e,
                    status = CalLogic.status(e, nowMs),
                    selected = e.key == selectedKey,
                    modifier = Modifier
                        .offset(x = GUTTER + 8.dp + laneWidth * lane, y = (e.startMin * DP_PER_MIN).dp)
                        .width(laneWidth - if (count > 1) 4.dp else 0.dp)
                        .height((maxOf(e.durationMin, 40) * DP_PER_MIN).dp),
                    onClick = { onSelect(e) },
                )
            }
            if (nowMin != null) {
                Box(Modifier.offset(x = GUTTER, y = (nowMin * DP_PER_MIN).dp).fillMaxWidth().height(2.dp).background(accent))
                Box(Modifier.offset(y = (nowMin * DP_PER_MIN).dp - 16.dp).fillMaxWidth().padding(end = 6.dp), contentAlignment = Alignment.TopEnd) {
                    FieldText("NOW " + Fmt.hhmm(nowMin), 10.sp, color = accent, tracking = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun EntryBlock(entry: Entry, status: EntryStatus, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val live = status == EntryStatus.LIVE
    val past = status == EntryStatus.PAST
    val border = when {
        selected -> accent
        past -> FieldColors.Dim
        else -> FieldColors.Ink
    }
    Column(
        modifier
            .background(FieldColors.Paper, CardShape)
            .border(if (selected) 2.dp else 1.5.dp, border, CardShape)
            .tap(onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live) BlinkingLed(LedMode.Slow)
            FieldText(entry.title.uppercase(), 12.sp, color = if (past) FieldColors.Dim else FieldColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry.durationMin >= 44) {
            val endText = Fmt.hhmm(entry.endMin)
            FieldText(
                if (live) "ON NOW / ENDS $endText"
                else Fmt.hhmm(entry.startMin) + "/" + endText + " / " + entry.durationMin + "M" +
                    (if (entry.location.isNotBlank()) " / " + entry.location.uppercase() else ""),
                10.sp, color = if (live) accent else FieldColors.Dim, tracking = 1.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Compact agenda list under the month grid. */
@Composable
fun AgendaList(entries: List<Entry>, nowMs: Long, selectedKey: String?, onSelect: (Entry) -> Unit) {
    entries.forEachIndexed { i, e ->
        FieldRow(
            index = i + 1,
            selected = e.key == selectedKey,
            onClick = { onSelect(e) },
            trailing = if (e.allDay) "ALL DAY" else Fmt.hhmm(e.startMin) + "/" + Fmt.hhmm(e.endMin),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (CalLogic.status(e, nowMs) == EntryStatus.LIVE) BlinkingLed(LedMode.Slow)
                FieldText(e.title.uppercase(), 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
