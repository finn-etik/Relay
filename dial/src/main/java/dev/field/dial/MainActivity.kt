package dev.field.dial

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.field.ui.BlinkingLed
import dev.field.ui.EmptyCard
import dev.field.ui.FieldColors
import dev.field.ui.FieldInput
import dev.field.ui.FieldKey
import dev.field.ui.FieldRow
import dev.field.ui.FieldScreen
import dev.field.ui.FieldText
import dev.field.ui.FieldToggleRow
import dev.field.ui.Fmt
import dev.field.ui.KeyVariant
import dev.field.ui.LedMode
import dev.field.ui.LocalAccent
import dev.field.ui.RegisterPanel
import dev.field.ui.SectionHeader
import dev.field.ui.SkinLine
import dev.field.ui.StatusHeader
import dev.field.ui.fieldContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DIAL: web radio stream player in the Field Design Language.
 * Skin: signal violet. Extension: input row.
 *
 * The screen only shows DialStore's state and sends commands; the stream
 * itself is played by DialService, so it survives the screen. Station search
 * uses the Radio Browser catalog (radio-browser.info, CC0 data).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DialStore.load(applicationContext)
        fieldContent(FieldColors.SignalViolet, FieldColors.SignalVioletOnDark) { DialApp() }
    }
}

@Composable
fun DialApp() {
    val context = LocalContext.current
    val app = context.applicationContext
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()

    val state by DialStore.state.collectAsState()
    val results by DialStore.results.collectAsState()

    var msg by remember { mutableStateOf("") }
    var nameIn by rememberSaveable { mutableStateOf("") }
    var urlIn by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }

    // Readouts are derived from the wall clock; `now` ticks only while something counts.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase, state.sleepAt) {
        while (state.phase == Phase.ON || state.sleepAt > 0L) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
        now = System.currentTimeMillis()
    }

    fun update(change: (DialState) -> DialState) = DialStore.update(app, change)

    // The playback notification needs permission on Android 13+. Asked on the
    // first TUNE; playback starts either way.
    var pendingId by remember { mutableIntStateOf(-1) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingId != -1) DialService.tune(app, pendingId)
        pendingId = -1
    }
    fun tune(st: Station) {
        msg = ""
        val ask = Build.VERSION.SDK_INT >= 33 && state.phase == Phase.OFF &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) {
            pendingId = st.id
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            DialService.tune(app, st.id)
        }
    }

    fun addStation() {
        val error = Dial.stationError(nameIn, urlIn, state.stations)
        if (error != null) {
            msg = error
            return
        }
        update { it.add(nameIn, urlIn) }
        nameIn = ""
        urlIn = ""
        msg = "STATION SAVED"
    }

    fun deleteStation() {
        val st = state.selected ?: return
        if (state.playing?.id == st.id) DialService.stop(app)
        update { it.delete(st.id) }
        msg = "STATION DELETED"
    }

    /** Searches the Radio Browser catalog; tap a result to save it as a station. */
    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) {
            msg = "TYPE A SEARCH TERM"
            return
        }
        if (searching) return
        searching = true
        DialStore.results.value = emptyList()
        scope.launch {
            val hits = try { RadioSearch.search(q) } catch (_: Exception) { null }
            searching = false
            msg = when {
                hits == null -> "SEARCH UNREACHABLE"
                hits.isEmpty() -> "NO STREAMS FOUND"
                else -> {
                    DialStore.results.value = hits
                    Fmt.pad2(hits.size) + " STREAMS FOUND"
                }
            }
        }
    }

    fun addHit(hit: SearchHit) {
        if (Dial.isSaved(hit.url, state.stations)) {
            msg = "ALREADY SAVED"
            return
        }
        update { it.add(hit.name, hit.url) }
        msg = "STATION SAVED"
    }

    val phase = state.phase
    val sel = state.selected
    val play = state.playing
    val led = when (phase) {
        Phase.ON -> LedMode.Slow
        Phase.CONNECT -> LedMode.Fast
        Phase.ERR -> LedMode.Solid
        Phase.OFF -> LedMode.Off
    }
    val status = when (phase) {
        Phase.ON -> "ON AIR"
        Phase.CONNECT -> "CONNECT"
        Phase.ERR -> "ERR"
        Phase.OFF -> "STANDBY"
    }
    val dimLine = when (phase) {
        Phase.ON -> play?.url ?: "STREAM LIVE / PLAYING"
        Phase.CONNECT -> if (state.attempts > 0) "RETRY " + Fmt.pad2(state.attempts) + " / CONNECTING" else "CONNECTING TO STREAM"
        Phase.ERR ->
            if (state.retry) "STREAM DOWN / RETRY IN " + Dial.retryDelayMs(state.attempts) / 1000 + " S"
            else "STREAM DOWN / AUTO RETRY OFF"
        Phase.OFF -> "NO ACTIVE STREAM"
    }
    val air = Fmt.clock(state.airMs(now))

    FieldScreen {
        StatusHeader(
            title = "DIAL",
            led = led,
            status = status,
            left = "SELECTED " + (sel?.name ?: "NONE"),
            right = Fmt.pad2(state.stations.size) + " STATIONS",
        )

        RegisterPanel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlinkingLed(led)
                FieldText(
                    play?.name ?: sel?.name ?: "NO STATION",
                    16.sp, Modifier.weight(1f), color = FieldColors.OnPanel,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                FieldText(if (state.muted) "MUTED" else "VOL " + Fmt.pad2(state.vol), 10.sp, color = FieldColors.Dim, tracking = 1.sp)
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                FieldText(
                    dimLine, 10.sp, Modifier.fillMaxWidth(), color = FieldColors.Dim, tracking = 1.sp,
                    align = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                FieldText(
                    air, 22.sp, Modifier.fillMaxWidth().padding(top = 4.dp), weight = FontWeight.Black,
                    color = if (phase == Phase.ON) accent else FieldColors.OnPanel, align = TextAlign.End,
                )
            }
            FieldText(
                "AIR " + air + " / TOTAL " + Fmt.clock(state.totalMs(now)) +
                    if (state.sleepAt > 0L) " / SLEEP " + Fmt.mmss(state.sleepRemainingMs(now)) else "",
                10.sp, Modifier.padding(top = 12.dp), color = accent, tracking = 1.sp, maxLines = 1,
            )
        }

        SectionHeader("TRANSPORT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey(
                "TUNE",
                {
                    when {
                        // Another station is selected: go there (DIAL 1.1 stopped instead).
                        sel != null && sel.id != play?.id -> tune(sel)
                        play != null -> DialService.stop(app)
                        else -> msg = "SELECT A STATION"
                    }
                },
                Modifier.weight(1f), size = 12.sp, variant = KeyVariant.Accent,
            )
            FieldKey("STOP", { DialService.stop(app) }, Modifier.weight(1f), size = 12.sp, enabled = phase != Phase.OFF)
        }

        SectionHeader("CONTROL")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey("VOL −", { update { it.volume(-1) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp)
            FieldKey(
                if (state.muted) "MUTE" else "VOL " + Fmt.pad2(state.vol),
                { update { it.copy(muted = !it.muted) } },
                Modifier.weight(1f), height = 40.dp, size = 12.sp, active = state.muted,
            )
            FieldKey("VOL +", { update { it.volume(1) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp)
        }
        FieldToggleRow("RETRY", "auto-reconnect when a stream drops", state.retry) {
            val on = !state.retry
            update { it.copy(retry = on) }
            // Switched on while a stream is down: start reconnecting now.
            if (on && phase == Phase.ERR && play != null) DialService.tune(app, play.id)
        }

        SectionHeader("SLEEP TIMER / MINUTES")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dial.SLEEP_MINUTES.forEach { minutes ->
                FieldKey(
                    Fmt.pad2(minutes),
                    { update { it.sleepIn(System.currentTimeMillis(), minutes) } },
                    Modifier.weight(1f), height = 40.dp, size = 12.sp,
                    enabled = phase == Phase.ON || phase == Phase.CONNECT,
                )
            }
            FieldKey(
                "OFF", { update { it.sleepIn(0L, 0) } },
                Modifier.weight(1f), height = 40.dp, size = 12.sp, enabled = state.sleepAt > 0L,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionHeader("STATIONS")
            FieldText(msg, 10.sp, color = FieldColors.Dim, tracking = 1.sp, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldInput(nameIn, { nameIn = it }, "NAME", Modifier.weight(0.5f), imeAction = ImeAction.Next)
            FieldInput(urlIn, { urlIn = it }, "STREAM URL", Modifier.weight(1f), onDone = { addStation() })
            FieldKey("ADD", { addStation() }, Modifier.weight(0.4f), size = 12.sp, variant = KeyVariant.Accent)
        }

        if (state.stations.isEmpty()) {
            EmptyCard("NO STATIONS", "ENTER A NAME AND STREAM URL, THEN ADD. OR SEARCH THE CATALOG BELOW.")
        } else {
            state.stations.forEachIndexed { i, st ->
                val isSel = st.id == state.selId
                FieldRow(
                    index = i + 1,
                    selected = isSel,
                    onClick = {
                        // First tap selects, second tap tunes.
                        if (isSel) tune(st) else {
                            update { it.select(st.id) }
                            msg = ""
                        }
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            FieldText(st.name, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            FieldText(st.url, 9.sp, Modifier.padding(top = 2.dp), color = FieldColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (play?.id == st.id) BlinkingLed(led)
                    }
                }
            }
            if (sel != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val at = state.stations.indexOf(sel)
                    FieldKey("UP", { update { it.move(sel.id, -1) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp, enabled = at > 0)
                    FieldKey("DOWN", { update { it.move(sel.id, 1) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp, enabled = at < state.stations.lastIndex)
                    FieldKey("DEL", { deleteStation() }, Modifier.weight(1f), height = 40.dp, size = 12.sp, variant = KeyVariant.Ink)
                }
            }
        }

        SectionHeader("SEARCH")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldInput(query, { query = it }, "SEARCH STREAMS", Modifier.weight(1f), imeAction = ImeAction.Search, onDone = { runSearch() })
            FieldKey(if (searching) "···" else "GO", { runSearch() }, Modifier.weight(0.3f), size = 12.sp, variant = KeyVariant.Accent, enabled = !searching)
        }
        results.forEachIndexed { i, hit ->
            val saved = Dial.isSaved(hit.url, state.stations)
            FieldRow(index = i + 1, selected = false, onClick = { addHit(hit) }, trailing = if (saved) "SAVED" else "ADD") {
                Column {
                    FieldText(hit.name, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FieldText(
                        Dial.meta(hit).ifEmpty { hit.url }, 9.sp, Modifier.padding(top = 2.dp), color = FieldColors.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL VIOLET / EXT: INPUT ROW")
    }
}
