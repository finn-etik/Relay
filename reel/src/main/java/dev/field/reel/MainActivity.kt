package dev.field.reel

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
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
import dev.field.ui.PaperCard
import dev.field.ui.RegisterPanel
import dev.field.ui.SectionHeader
import dev.field.ui.SkinLine
import dev.field.ui.StatusHeader
import dev.field.ui.fieldEdgeToEdge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * REEL: voice recorder in the Field Design Language. Skin: signal red.
 * EXT: TRANSCRIPT, by Parakeet TDT 0.6B v3 running on the phone.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReelStore.load(applicationContext)
        Transcriber.init(applicationContext)
        ParakeetModel.refresh(applicationContext)
        fieldEdgeToEdge()
        setContent { FieldTheme(FieldColors.SignalRed) { ReelApp() } }
    }
}

private class Settings(context: Context) {
    private val p = context.getSharedPreferences("reel", Context.MODE_PRIVATE)
    var liveTxt: Boolean
        get() = p.getBoolean("live", false)
        set(v) = p.edit().putBoolean("live", v).apply()
    var mobileData: Boolean
        get() = p.getBoolean("mobile", false)
        set(v) = p.edit().putBoolean("mobile", v).apply()
}

@Composable
fun ReelApp() {
    val context = LocalContext.current
    val app = context.applicationContext
    val settings = remember { Settings(app) }
    val scope = rememberCoroutineScope()

    val takes by ReelStore.takes.collectAsState()
    val rec by ReelStore.rec.collectAsState()
    val asr by ReelStore.asr.collectAsState()
    val model by ParakeetModel.status.collectAsState()

    var selId by rememberSaveable { mutableIntStateOf(-1) }
    var liveTxt by remember { mutableStateOf(settings.liveTxt) }
    var mobileData by remember { mutableStateOf(settings.mobileData) }
    var playingId by remember { mutableIntStateOf(-1) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDel by remember { mutableStateOf(false) }

    fun flash(text: String) {
        notice = text
        scope.launch { delay(1500); if (notice == text) notice = null }
    }

    // Model download progress: poll the system DownloadManager while visible.
    LaunchedEffect(model is ParakeetModel.Status.Downloading) {
        while (ParakeetModel.status.value is ParakeetModel.Status.Downloading) {
            delay(1000)
            ParakeetModel.refresh(app)
        }
    }

    // A take just finished: select it.
    var lastCount by remember { mutableIntStateOf(takes.size) }
    LaunchedEffect(takes.size) {
        if (takes.size > lastCount) takes.firstOrNull()?.let { selId = it.id }
        lastCount = takes.size
    }

    fun stopPlayback() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        playingId = -1
    }
    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) RecorderService.start(app, liveTxt && ParakeetModel.isReady(app))
        else ReelStore.rec.value = RecState(error = "NO MIC")
    }

    val selTake = takes.firstOrNull { it.id == selId }
    val phase = rec.phase
    val recording = phase != RecPhase.STANDBY
    val playing = playingId != -1

    fun recKey() {
        if (playing) return
        if (recording) {
            RecorderService.toggleHold(app)
            return
        }
        val need = buildList {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) RecorderService.start(app, liveTxt && ParakeetModel.isReady(app))
        else micAsk.launch(need.toTypedArray())
    }

    fun play() {
        if (recording) return
        val take = selTake ?: takes.firstOrNull() ?: return
        stopPlayback()
        selId = take.id
        try {
            val mp = MediaPlayer()
            mp.setDataSource(ReelStore.file(take).absolutePath)
            mp.prepare()
            mp.setOnCompletionListener { stopPlayback() }
            mp.start()
            player = mp
            playingId = take.id
        } catch (_: Exception) {
            ReelStore.rec.value = RecState(error = "PLAYBACK")
        }
    }

    fun stop() {
        if (recording) RecorderService.stop(app) else stopPlayback()
    }

    fun delete() {
        val take = selTake ?: return
        if (!confirmDel) {
            confirmDel = true
            scope.launch { delay(3000); confirmDel = false }
            return
        }
        confirmDel = false
        if (playingId == take.id) stopPlayback()
        ReelStore.delete(take.id)
        selId = -1
    }

    fun share(take: Take) {
        val uri = FileProvider.getUriForFile(context, "dev.field.reel.files", ReelStore.file(take))
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (take.transcript.isNotEmpty()) send.putExtra(Intent.EXTRA_TEXT, take.transcript)
        send.clipData = ClipData.newRawUri(take.fileName, uri)
        context.startActivity(Intent.createChooser(send, take.fileName))
    }

    // ---- derived readouts ----
    val nextTake = (takes.maxOfOrNull { it.id } ?: 0) + 1
    val selNo = selTake?.let { takes.indexOf(it) + 1 } ?: 0
    val led = when {
        phase == RecPhase.REC || playing -> LedMode.Slow
        phase == RecPhase.HOLD -> LedMode.Solid
        else -> LedMode.Off
    }
    val status = when {
        phase == RecPhase.REC -> "ON AIR"
        phase == RecPhase.HOLD -> "HOLD"
        playing -> "PLAY"
        else -> "STANDBY"
    }
    val error = rec.error ?: asr.error
    val dimLine = when {
        error != null -> "ERR / $error"
        asr.loading -> "LOADING PARAKEET"
        phase == RecPhase.REC && rec.live -> "REC / LIVE TXT / SEG " + Fmt.pad3(asr.segments)
        phase == RecPhase.REC -> "REC / INPUT LIVE"
        phase == RecPhase.HOLD -> "HELD / CLOCK STOPPED"
        playing -> "PLAYBACK / TAKE " + Fmt.pad3(selNo)
        asr.busyTakeId != 0 && asr.progress >= 0 -> "TRANSCRIBING " + (asr.progress * 100).toInt() + "%"
        takes.isNotEmpty() -> "SELECT A TAKE / PLAY TO AUDITION"
        else -> "NO TAKES / PRESS REC"
    }
    val big = when {
        recording -> Fmt.clock(rec.recordedMs)
        selTake != null -> Fmt.clock(selTake.durationMs)
        else -> "00:00:00"
    }
    val red = FieldColors.SignalRed
    val modelReady = model is ParakeetModel.Status.Ready
    val engine = when {
        !liveTxt -> "OFF"
        modelReady -> "PARAKEET V3"
        else -> "NO MODEL"
    }

    FieldScreen {
        StatusHeader("REEL", led, status, left = "TAKE " + Fmt.pad3(if (recording) rec.takeId else nextTake), right = "ASR $engine")

        RegisterPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BlinkingLed(led)
                    FieldText(if (recording) "TAKE " + Fmt.pad3(rec.takeId) else "TAPE", 16.sp, color = FieldColors.Paper)
                }
                FieldText(
                    when {
                        phase == RecPhase.REC -> "IN"
                        playing -> "OUT"
                        phase == RecPhase.HOLD -> "HELD"
                        else -> "IDLE"
                    },
                    10.sp, color = FieldColors.Dim, tracking = 1.sp,
                )
            }
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                FieldText(dimLine, 10.sp, Modifier.fillMaxWidth(), color = if (error != null) red else FieldColors.Dim, tracking = 1.sp, align = TextAlign.End, maxLines = 2)
                FieldText(big, 22.sp, Modifier.fillMaxWidth().padding(top = 4.dp), weight = FontWeight.Black, color = if (error != null) red else FieldColors.Paper, align = TextAlign.End)
                if (phase == RecPhase.REC) LevelMeter(rec.level)
            }
            FieldText(
                "TAPE ${Fmt.clock(takes.sumOf { it.durationMs } + rec.recordedMs)} / TAKES " + Fmt.pad3(takes.size),
                10.sp, Modifier.padding(top = 12.dp), color = red, tracking = 1.sp,
            )
        }

        SectionHeader("TRANSPORT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey(if (phase == RecPhase.REC) "HOLD" else if (phase == RecPhase.HOLD) "RESUME" else "REC", { recKey() }, Modifier.weight(1f), size = 12.sp, variant = KeyVariant.Accent, enabled = !playing)
            FieldKey("PLAY", { play() }, Modifier.weight(1f), size = 12.sp, enabled = !recording && takes.isNotEmpty())
            FieldKey("STOP", { stop() }, Modifier.weight(1f), size = 12.sp)
            FieldKey(if (confirmDel) "SURE?" else "DEL", { delete() }, Modifier.weight(1f), size = 12.sp, variant = KeyVariant.Ink, enabled = selTake != null && !recording)
        }

        SectionHeader("CONTROL")
        FieldToggleRow("LIVE TXT", "parakeet v3 on device / 25 languages, auto-detected", liveTxt) {
            liveTxt = !liveTxt
            settings.liveTxt = liveTxt
            if (liveTxt && !modelReady) flash("DOWNLOAD THE MODEL FIRST")
        }
        ModelCard(model, ParakeetModel.totalBytes(app), mobileData,
            onDownload = { ParakeetModel.download(app, mobileData) },
            onCancel = { ParakeetModel.cancel(app) },
            onDelete = { ParakeetModel.delete(app) },
            onToggleMobile = { mobileData = !mobileData; settings.mobileData = mobileData },
        )

        SectionHeader("TAKES")
        if (takes.isEmpty()) {
            EmptyCard("NO TAKES", "PRESS REC TO LOG ONE. TAP A ROW TO SELECT, PLAY TO AUDITION.")
        } else {
            takes.forEachIndexed { i, take ->
                FieldRow(
                    index = i + 1,
                    selected = take.id == selId,
                    onClick = { selId = take.id; confirmDel = false },
                    trailing = Fmt.clock(take.durationMs) + " / " + Fmt.kilobytes(take.sizeBytes) + if (take.transcript.isNotEmpty()) " / TXT" else "",
                ) {
                    FieldText("TAKE " + Fmt.pad3(take.id), 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (selTake != null) {
            val busy = asr.busyTakeId == selTake.id
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeader("TRANSCRIPT", Modifier.weight(1f))
                FieldKey(if (busy) "···" else "TXT", { if (modelReady) Transcriber.transcribeTake(selTake) else flash("DOWNLOAD THE MODEL FIRST") }, Modifier.width(56.dp), height = 26.dp, size = 10.sp, enabled = !busy && !(recording && rec.takeId == selTake.id))
                if (selTake.transcript.isNotEmpty()) {
                    FieldKey(notice?.takeIf { it == "COPIED" } ?: "CPY", {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("TRANSCRIPT", selTake.transcript))
                        flash("COPIED")
                    }, Modifier.width(72.dp), height = 26.dp, size = 10.sp)
                }
                FieldKey("SHARE", { share(selTake) }, Modifier.width(64.dp), height = 26.dp, size = 10.sp)
            }
            if (selTake.transcript.isNotEmpty()) {
                RegisterPanel {
                    FieldText(selTake.transcript, 11.sp, Modifier.fillMaxWidth(), weight = FontWeight.Normal, color = FieldColors.Paper, lineHeight = 16.sp)
                }
            } else {
                PaperCard {
                    FieldText(
                        when {
                            busy && asr.loading -> "LOADING MODEL…"
                            busy -> "TRANSCRIBING " + maxOf(0, (asr.progress * 100).toInt()) + "%"
                            modelReady -> "NOT TRANSCRIBED / PRESS TXT, OR ARM LIVE TXT BEFORE REC"
                            else -> "NOT TRANSCRIBED / DOWNLOAD THE PARAKEET MODEL IN CONTROL"
                        },
                        10.sp, color = FieldColors.Dim, lineHeight = 15.sp,
                    )
                }
            }
        }

        notice?.takeIf { it != "COPIED" }?.let { FieldText("! $it", 10.sp, color = red, tracking = 1.sp) }
        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL RED / EXT: TRANSCRIPT (PARAKEET V3)")
    }
}

@Composable
private fun LevelMeter(level: Float) {
    // 24 hard segments, no smoothing: an instrument meter, not an animation.
    val lit = (level * 24).toInt().coerceIn(0, 24)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until 24) {
            Box(Modifier.weight(1f).fillMaxHeight().background(if (i < lit) FieldColors.SignalRed else FieldColors.Paper.copy(alpha = 0.15f)))
        }
    }
}

@Composable
private fun ModelCard(
    status: ParakeetModel.Status,
    totalBytes: Long,
    mobileData: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onToggleMobile: () -> Unit,
) {
    PaperCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                FieldText("MODEL / PARAKEET TDT 0.6B V3", 12.sp, tracking = 1.sp)
                FieldText(
                    when (status) {
                        ParakeetModel.Status.NotInstalled -> "NOT INSTALLED / " + Fmt.megabytes(totalBytes) + " ONE-TIME DOWNLOAD"
                        is ParakeetModel.Status.Downloading ->
                            (if (status.waitingForWifi) "WAITING FOR WI-FI / " else "DOWNLOADING / ") +
                                Fmt.megabytes(status.done) + " OF " + Fmt.megabytes(status.total)
                        ParakeetModel.Status.Verifying -> "VERIFYING CHECKSUMS…"
                        ParakeetModel.Status.Ready -> "READY / OFFLINE / 25 LANGUAGES"
                        is ParakeetModel.Status.Failed -> "ERR / " + status.reason
                    },
                    9.sp, color = if (status is ParakeetModel.Status.Failed) FieldColors.SignalRed else FieldColors.Dim,
                )
            }
        }
        // CC-BY-4.0 attribution for the model weights.
        FieldText("NVIDIA PARAKEET TDT 0.6B V3 / CC-BY-4.0 / ONNX EXPORT: SHERPA-ONNX", 8.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim)
        if (status is ParakeetModel.Status.Downloading && status.total > 0) {
            val lit = (status.done * 24 / status.total).toInt().coerceIn(0, 24)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until 24) Box(Modifier.weight(1f).fillMaxHeight().background(if (i < lit) FieldColors.SignalRed else FieldColors.Grid))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                ParakeetModel.Status.NotInstalled, is ParakeetModel.Status.Failed ->
                    FieldKey("DOWNLOAD", onDownload, Modifier.weight(1f), height = 40.dp, size = 12.sp, variant = KeyVariant.Accent)
                is ParakeetModel.Status.Downloading ->
                    FieldKey("CANCEL", onCancel, Modifier.weight(1f), height = 40.dp, size = 12.sp)
                ParakeetModel.Status.Verifying -> Unit
                ParakeetModel.Status.Ready ->
                    FieldKey("DELETE MODEL", onDelete, Modifier.weight(1f), height = 40.dp, size = 12.sp, variant = KeyVariant.Ink)
            }
        }
        if (status !is ParakeetModel.Status.Ready) {
            Box(Modifier.padding(top = 8.dp)) {
                FieldToggleRow("MOBILE DATA", "allow the download without wi-fi", mobileData, onToggleMobile)
            }
        }
    }
}
