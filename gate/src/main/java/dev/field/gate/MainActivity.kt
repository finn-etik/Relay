package dev.field.gate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.field.ui.BlinkingLed
import dev.field.ui.EmptyCard
import dev.field.ui.FieldColors
import dev.field.ui.FieldKey
import dev.field.ui.FieldRow
import dev.field.ui.FieldScreen
import dev.field.ui.FieldText
import dev.field.ui.FieldTheme
import dev.field.ui.FieldToggleRow
import dev.field.ui.Fmt
import dev.field.ui.KeyVariant
import dev.field.ui.LedMode
import dev.field.ui.RegisterPanel
import dev.field.ui.SectionHeader
import dev.field.ui.SkinLine
import dev.field.ui.StatusHeader
import dev.field.ui.fieldEdgeToEdge
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** GATE: focus timer in the Field Design Language. Skin: signal blue. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GateStore.load(applicationContext)
        fieldEdgeToEdge()
        setContent { FieldTheme(FieldColors.SignalBlue) { GateApp() } }
    }
}

@Composable
fun GateApp() {
    val context = LocalContext.current.applicationContext
    val state by GateStore.state.collectAsState()

    // The readout is derived from the wall clock. `now` is read below, so the
    // screen really recomposes every second (the original's tick never was).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.phase) {
        while (state.phase == Phase.ON || state.phase == Phase.DONE) {
            now = System.currentTimeMillis()
            GateStore.update(context) { it.complete(now).settle(now) } // in case the alarm is late
            delay(1000 - now % 1000) // tick on the second boundary
        }
        now = System.currentTimeMillis()
    }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        GateStore.start(context) // start either way; notifications are optional
    }
    fun start() {
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needAsk && state.phase == Phase.STANDBY) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else GateStore.start(context)
    }

    val phase = state.phase
    val led = when (phase) {
        Phase.ON -> LedMode.Slow
        Phase.DONE -> LedMode.Fast
        Phase.HOLD -> LedMode.Solid
        Phase.STANDBY -> LedMode.Off
    }
    val status = when (phase) {
        Phase.ON -> "ON AIR"
        Phase.DONE -> "DONE"
        Phase.HOLD -> "HOLD"
        Phase.STANDBY -> "STANDBY"
    }
    val dimLine = when (phase) {
        Phase.ON -> "WORK / COUNTING DOWN"
        Phase.HOLD -> "CLOCK STOPPED"
        Phase.DONE -> "SESSION COMPLETE / LOGGED"
        Phase.STANDBY -> "NO ACTIVE SESSION"
    }
    val blue = FieldColors.SignalBlue
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.ROOT) }

    FieldScreen {
        StatusHeader(
            title = "GATE",
            led = led,
            status = status,
            left = "PRESET ${state.presetMin} MIN",
            right = "SESSIONS " + Fmt.pad3(state.log.size),
        )

        RegisterPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BlinkingLed(led)
                    FieldText("SESSION", 16.sp, color = FieldColors.Paper)
                }
                FieldText(
                    when (phase) {
                        Phase.ON -> "COUNTING"
                        Phase.HOLD -> "HELD"
                        Phase.DONE -> "COMPLETE"
                        Phase.STANDBY -> "READY"
                    },
                    10.sp, color = FieldColors.Dim, tracking = 1.sp,
                )
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                FieldText(dimLine, 10.sp, Modifier.fillMaxWidth(), color = FieldColors.Dim, tracking = 1.sp, align = TextAlign.End)
                FieldText(
                    Fmt.mmss(state.remainingMs(now)),
                    22.sp, Modifier.fillMaxWidth().padding(top = 4.dp),
                    weight = FontWeight.Black,
                    color = if (phase == Phase.DONE) blue else FieldColors.Paper,
                    align = TextAlign.End,
                )
            }
            FieldText(
                "ELAPSED ${Fmt.clock(state.elapsedMs(now))} / DONE ${Fmt.pad3(state.log.size)}",
                10.sp, Modifier.padding(top = 12.dp), color = blue, tracking = 1.sp,
            )
        }

        SectionHeader("CONTROL")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GateState.PRESETS.forEach { p ->
                FieldKey(
                    label = Fmt.pad2(p),
                    onPress = { GateStore.selectPreset(context, p) },
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    active = state.presetMin == p && phase == Phase.STANDBY,
                    enabled = phase == Phase.STANDBY,
                )
            }
        }
        FieldToggleRow("BEEP", "audio cue at session end, also with the app closed", state.beep) {
            GateStore.toggleBeep(context)
        }

        SectionHeader("TRANSPORT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey(if (phase == Phase.HOLD) "RESUME" else "START", { start() }, Modifier.weight(1f), size = 12.sp, enabled = phase != Phase.ON)
            FieldKey("HOLD", { GateStore.hold(context) }, Modifier.weight(1f), size = 12.sp, enabled = phase == Phase.ON)
            FieldKey("STOP", { GateStore.stop(context) }, Modifier.weight(1f), size = 12.sp, variant = KeyVariant.Accent)
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("LOG", Modifier.weight(1f))
            if (state.log.isNotEmpty()) {
                FieldKey("CLR LOG", { GateStore.clearLog(context) }, Modifier.width(72.dp), height = 26.dp, size = 10.sp)
            }
        }
        if (state.log.isEmpty()) {
            EmptyCard("NO SESSIONS", "RUN ONE TO LOG IT. TAP A ROW TO RECALL ITS PRESET.")
        } else {
            state.log.forEachIndexed { i, entry ->
                FieldRow(index = i + 1, selected = false, onClick = { GateStore.selectPreset(context, entry.minutes) }, trailing = "DONE") {
                    FieldText("${entry.minutes} MIN / ENDED ${timeFormat.format(Date(entry.endedAt))}", 12.sp, tracking = 1.sp)
                }
            }
        }

        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL BLUE / NO EXTENSIONS")
    }
}
