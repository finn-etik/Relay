package dev.field.dial

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * DIAL: web radio stream player in the Field Design Language.
 * Skin: signal violet. Extension: input row.
 *
 * Playback is the framework MediaPlayer (HTTP/ICY streams, prepareAsync).
 * Stations persist as JSON in SharedPreferences. Station search uses the
 * Radio Browser catalog (radio-browser.info, CC0 data).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fieldContent(FieldColors.SignalViolet, FieldColors.SignalVioletOnDark) { DialApp() }
    }
}

/** SharedPreferences file and keys (unchanged from DIAL 1.1). */
private object Prefs {
    const val FILE = "field_dial"
    const val STATIONS = "STATIONS"
    const val SEL = "SEL"
    const val TOTAL = "TOTAL"
    const val VOL = "VOL"
    const val RETRY = "RETRY"
}

private fun loadStations(prefs: SharedPreferences): List<Station> {
    val list = ArrayList<Station>()
    try {
        val arr = JSONArray(prefs.getString(Prefs.STATIONS, "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Station(o.getInt("id"), o.getString("n"), o.getString("u")))
        }
    } catch (_: Exception) {
        list.clear()
    }
    // Seed only a phone that never had a list; an emptied list stays empty.
    return if (list.isEmpty() && !prefs.contains(Prefs.STATIONS)) Dial.SEED else list
}

@Composable
fun DialApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE) }
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()

    val stations = remember { mutableStateListOf<Station>().apply { addAll(loadStations(prefs)) } }
    var nextId by remember { mutableIntStateOf((stations.maxOfOrNull { it.id } ?: 0) + 1) }

    var selId by remember { mutableIntStateOf(prefs.getInt(Prefs.SEL, -1)) }
    var play by remember { mutableStateOf<Station?>(null) }
    var phase by remember { mutableStateOf(Phase.OFF) }
    var airtime by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(prefs.getInt(Prefs.TOTAL, 0)) }
    var vol by remember { mutableIntStateOf(prefs.getInt(Prefs.VOL, 7).coerceIn(0, Dial.MAX_VOLUME)) }
    var muted by remember { mutableStateOf(false) }
    var retryOn by remember { mutableStateOf(prefs.getBoolean(Prefs.RETRY, true)) }
    var attempts by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var nameIn by remember { mutableStateOf("") }
    var urlIn by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<SearchHit>() }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun saveStations() {
        val arr = JSONArray()
        stations.forEach { arr.put(JSONObject().put("id", it.id).put("n", it.name).put("u", it.url)) }
        prefs.edit().putString(Prefs.STATIONS, arr.toString()).apply()
    }
    fun persistTotal() = prefs.edit().putInt(Prefs.TOTAL, total).apply()
    fun volumeFraction(): Float = if (muted) 0f else vol / Dial.MAX_VOLUME.toFloat()

    fun releasePlayer() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null
    }

    /** A stream that errors or simply ends has dropped. Ignores callbacks from a player already replaced. */
    fun dropped(mp: MediaPlayer) {
        if (player !== mp) return
        try { mp.release() } catch (_: Exception) {}
        player = null
        phase = Phase.ERR
    }

    /** [retry] = an automatic reconnect: the attempt count and airtime carry on. */
    fun tune(st: Station, retry: Boolean = false) {
        releasePlayer()
        persistTotal()
        play = st
        selId = st.id
        prefs.edit().putInt(Prefs.SEL, st.id).apply()
        phase = Phase.CONNECT
        msg = ""
        if (!retry) {
            airtime = 0
            attempts = 0
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(st.url)
            val v = volumeFraction()
            mp.setVolume(v, v)
            mp.setOnPreparedListener { ready ->
                if (player === ready) {
                    ready.start()
                    phase = Phase.ON
                    attempts = 0
                }
            }
            mp.setOnErrorListener { failed, _, _ ->
                dropped(failed)
                true
            }
            mp.setOnCompletionListener { ended -> dropped(ended) }
            player = mp
            mp.prepareAsync()
        } catch (_: Exception) {
            try { mp.release() } catch (_: Exception) {}
            player = null
            phase = Phase.ERR
        }
    }

    fun stop() {
        releasePlayer()
        persistTotal()
        phase = Phase.OFF
        play = null
        airtime = 0
        attempts = 0
    }

    fun select(id: Int) {
        selId = id
        prefs.edit().putInt(Prefs.SEL, id).apply()
    }

    fun addStation() {
        val error = Dial.stationError(nameIn, urlIn, stations)
        if (error != null) {
            msg = error
            return
        }
        stations.add(Station(nextId++, nameIn.trim().uppercase(Locale.ROOT), urlIn.trim()))
        saveStations()
        nameIn = ""
        urlIn = ""
        msg = "STATION SAVED"
    }

    fun deleteStation() {
        val st = stations.find { it.id == selId } ?: return
        if (play?.id == st.id) stop()
        stations.remove(st)
        saveStations()
        select(-1)
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
        results.clear()
        scope.launch {
            val hits = try { RadioSearch.search(q) } catch (_: Exception) { null }
            searching = false
            msg = when {
                hits == null -> "SEARCH UNREACHABLE"
                hits.isEmpty() -> "NO STREAMS FOUND"
                else -> {
                    results.addAll(hits)
                    Fmt.pad2(hits.size) + " STREAMS FOUND"
                }
            }
        }
    }

    fun addHit(hit: SearchHit) {
        if (stations.any { it.url.equals(hit.url, ignoreCase = true) }) {
            msg = "ALREADY SAVED"
            return
        }
        stations.add(Station(nextId++, hit.name, hit.url))
        saveStations()
        msg = "STATION SAVED"
    }

    LaunchedEffect(vol) { prefs.edit().putInt(Prefs.VOL, vol).apply() }
    LaunchedEffect(retryOn) { prefs.edit().putBoolean(Prefs.RETRY, retryOn).apply() }

    // Live volume changes.
    LaunchedEffect(vol, muted) {
        val v = volumeFraction()
        try { player?.setVolume(v, v) } catch (_: Exception) {}
    }

    // Auto retry after a drop, backing off from 3 s to a minute. Keyed on
    // retryOn too, so switching RETRY on while a stream is down re-arms it.
    LaunchedEffect(phase, retryOn) {
        val st = play
        if (phase == Phase.ERR && retryOn && st != null) {
            delay(Dial.retryDelayMs(attempts))
            attempts += 1
            tune(st, retry = true)
        }
    }

    // Airtime: 1 s tick while on air.
    LaunchedEffect(phase) {
        if (phase != Phase.ON) return@LaunchedEffect
        while (true) {
            delay(1000)
            airtime += 1
            total += 1
            if (total % 15 == 0) persistTotal() // also saved on stop, re-tune and exit
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            releasePlayer()
            persistTotal()
        }
    }

    val sel = stations.find { it.id == selId }
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
        Phase.ON -> "STREAM LIVE / PLAYING"
        Phase.CONNECT -> if (attempts > 0) "RETRY " + Fmt.pad2(attempts) + " / CONNECTING" else "CONNECTING TO STREAM"
        Phase.ERR ->
            if (retryOn) "STREAM DOWN / RETRY IN " + Dial.retryDelayMs(attempts) / 1000 + " S"
            else "STREAM DOWN / AUTO RETRY OFF"
        Phase.OFF -> "NO ACTIVE STREAM"
    }

    FieldScreen {
        StatusHeader(
            title = "DIAL",
            led = led,
            status = status,
            left = "SELECTED " + (sel?.name ?: "NONE"),
            right = Fmt.pad2(stations.size) + " STATIONS",
        )

        RegisterPanel {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BlinkingLed(led)
                FieldText(
                    play?.name ?: sel?.name ?: "NO STATION",
                    16.sp, Modifier.weight(1f), color = FieldColors.OnPanel,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                FieldText(if (muted) "MUTED" else "VOL " + Fmt.pad2(vol), 10.sp, color = FieldColors.Dim, tracking = 1.sp)
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                FieldText(
                    if (phase == Phase.ON) play?.url ?: dimLine else dimLine,
                    10.sp, Modifier.fillMaxWidth(), color = FieldColors.Dim, tracking = 1.sp,
                    align = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                FieldText(
                    Fmt.clock(airtime * 1000L),
                    22.sp, Modifier.fillMaxWidth().padding(top = 4.dp), weight = FontWeight.Black,
                    color = if (phase == Phase.ON) accent else FieldColors.OnPanel, align = TextAlign.End,
                )
            }
            FieldText(
                "AIR " + Fmt.clock(airtime * 1000L) + " / TOTAL " + Fmt.clock(total * 1000L),
                10.sp, Modifier.padding(top = 12.dp), color = accent, tracking = 1.sp,
            )
        }

        SectionHeader("CONTROL")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey("VOL −", { muted = false; if (vol > 0) vol -= 1 }, Modifier.weight(1f), height = 40.dp, size = 12.sp)
            FieldKey(if (muted) "MUTE" else "VOL " + Fmt.pad2(vol), { muted = !muted }, Modifier.weight(1f), height = 40.dp, size = 12.sp, active = muted)
            FieldKey("VOL +", { muted = false; if (vol < Dial.MAX_VOLUME) vol += 1 }, Modifier.weight(1f), height = 40.dp, size = 12.sp)
        }
        FieldToggleRow("RETRY", "auto-reconnect when a stream drops", retryOn) { retryOn = !retryOn }

        SectionHeader("TRANSPORT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey(
                "TUNE",
                {
                    when {
                        // Another station is selected: go there (this used to stop instead).
                        sel != null && sel.id != play?.id -> tune(sel)
                        play != null -> stop()
                        else -> msg = "SELECT A STATION"
                    }
                },
                Modifier.weight(1f), size = 12.sp, variant = KeyVariant.Accent,
            )
            FieldKey("STOP", { stop() }, Modifier.weight(1f), size = 12.sp, enabled = phase != Phase.OFF)
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

        if (stations.isEmpty()) {
            EmptyCard("NO STATIONS", "ENTER A NAME AND STREAM URL, THEN ADD. OR SEARCH THE CATALOG BELOW.")
        } else {
            stations.forEachIndexed { i, st ->
                val isSel = st.id == selId
                FieldRow(
                    index = i + 1,
                    selected = isSel,
                    onClick = {
                        // First tap selects, second tap tunes.
                        if (isSel) tune(st) else {
                            select(st.id)
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
                FieldKey("DEL", { deleteStation() }, Modifier.fillMaxWidth(), height = 40.dp, size = 12.sp, variant = KeyVariant.Ink)
            }
        }

        SectionHeader("SEARCH")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldInput(query, { query = it }, "SEARCH STREAMS", Modifier.weight(1f), imeAction = ImeAction.Search, onDone = { runSearch() })
            FieldKey(if (searching) "···" else "GO", { runSearch() }, Modifier.weight(0.3f), size = 12.sp, variant = KeyVariant.Accent, enabled = !searching)
        }
        results.forEachIndexed { i, hit ->
            FieldRow(index = i + 1, selected = false, onClick = { addHit(hit) }, trailing = "ADD") {
                Column {
                    FieldText(hit.name, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val meta = Dial.meta(hit)
                    FieldText(
                        meta.ifEmpty { hit.url }, 9.sp, Modifier.padding(top = 2.dp), color = FieldColors.Dim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL VIOLET / EXT: INPUT ROW")
    }
}
