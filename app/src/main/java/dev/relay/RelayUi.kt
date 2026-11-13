package dev.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

// Teenage Engineering inspired: field-gray body, ink-black rules,
// signal-orange LEDs, square corners, monospace technical labels.

private val TeBody = Color(0xFFDADAD4)
private val TePaper = Color(0xFFF5F5F1)
private val TeInk = Color(0xFF191919)
private val TeDim = Color(0xFF6E6E67) // darkened from 8A8A82 for contrast on gray
private val TeOrange = Color(0xFFFF3C00)
private val TeGrid = Color(0xFFC4C4BC)
private val Corner = RoundedCornerShape(4.dp)
private val SmallCorner = RoundedCornerShape(3.dp)

@Composable
fun RelayScreen(
    speakers: List<SonosSpeaker>,
    scanning: Boolean,
    wifiLock: Boolean,
    onSelect: (SonosSpeaker) -> Unit,
    onStop: () -> Unit,
    onRescan: () -> Unit,
    onVolume: (Int) -> Unit,
    onWifiLock: () -> Unit
) {
    val session by RelayState.session.collectAsState()
    val message by RelayState.message.collectAsState()
    val volume by RelayState.volume.collectAsState()
    val live = session

    Box(
        Modifier
            .fillMaxSize()
            .background(TeBody)
            .systemBarsPadding()
    ) {
        Dots()

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            // Device header: name, status LED, state readout.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("RELAY", TeInk, 22, FontWeight.Black, spacing = 2f)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(8.dp).background(if (live != null) TeOrange else TeGrid))
                Spacer(Modifier.width(8.dp))
                Label(
                    if (live != null) "ON AIR" else "STANDBY",
                    if (live != null) TeOrange else TeDim,
                    11, FontWeight.Bold
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(TeInk))
            Spacer(Modifier.height(10.dp))
            Label(
                if (live != null) "PHONE MUTED / OUTPUT: ${live.speaker.name.uppercase()}"
                else "PHONE AUDIO -> SONOS",
                TeDim, 10, spacing = 1f
            )

            message?.let {
                Spacer(Modifier.height(8.dp))
                Label("! $it", TeOrange, 10, FontWeight.Bold, spacing = 1f, lineHeight = 14)
            }

            Spacer(Modifier.height(18.dp))

            if (live != null) {
                ActivePanel(
                    session = live,
                    volume = volume,
                    onVolumeDown = { onVolume(-1) },
                    onVolumeUp = { onVolume(1) },
                    onStop = onStop
                )
                Spacer(Modifier.height(18.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("SPEAKERS", TeDim, 10, FontWeight.Bold, spacing = 2f)
                Spacer(Modifier.weight(1f))
                if (!scanning) Key("RESCAN", onRescan, fill = TePaper, ink = TeInk, small = true)
            }
            Spacer(Modifier.height(10.dp))

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    scanning -> ScanningLine()
                    speakers.isEmpty() -> EmptyState()
                    else -> speakers.forEachIndexed { index, speaker ->
                        SpeakerRow(
                            index = index,
                            speaker = speaker,
                            isActiveTarget = live?.speaker?.ip == speaker.ip,
                            onClick = { onSelect(speaker) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            PrefRow(
                label = "WI-FI LOCK",
                caption = "FEWER DROPOUTS / MORE POWER",
                checked = wifiLock,
                onToggle = onWifiLock
            )
        }
    }
}

@Composable
private fun Label(
    text: String,
    color: Color,
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    spacing: Float = 0f,
    lineHeight: Int = 0,
    modifier: Modifier = Modifier
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontWeight = weight,
            fontFamily = FontFamily.Monospace,
            letterSpacing = spacing.sp,
            lineHeight = if (lineHeight > 0) lineHeight.sp else TextUnit.Unspecified
        )
    )
}

/** Hardware-style key: inverts while pressed instead of a ripple. */
@Composable
private fun Key(
    label: String,
    onClick: () -> Unit,
    fill: Color,
    ink: Color,
    small: Boolean = false,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .background(if (pressed) ink else fill, SmallCorner)
            .border(1.5.dp, ink, SmallCorner)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 10.dp else 16.dp, vertical = if (small) 5.dp else 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Label(label, if (pressed) fill else ink, if (small) 10 else 12, FontWeight.Black, spacing = 1f)
    }
}

@Composable
private fun Dots() {
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val step = 26.dp.toPx()
                val radius = 1.dp.toPx()
                // Read the canvas size here: inside buildList, `size` would
                // mean the list's item count.
                val width = size.width
                val height = size.height
                // Built once per size and cached; never rebuilt per frame.
                val dots = buildList {
                    var y = step
                    while (y < height) {
                        var x = step
                        while (x < width) {
                            add(Offset(x, y))
                            x += step
                        }
                        y += step
                    }
                }
                onDrawBehind {
                    for (center in dots) drawCircle(TeGrid, radius = radius, center = center)
                }
            }
    )
}

@Composable
private fun ActivePanel(
    session: Session,
    volume: Int,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(TeInk, Corner)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BlinkingLed(TeOrange, size = 10, onTime = 250, offTime = 750)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Label(session.speaker.name.uppercase(), TePaper, 16, FontWeight.Bold)
                Label("AAC 256K / LIVE", TeGrid, 10, spacing = 1f)
            }
            Key("STOP", onStop, fill = TeOrange, ink = TeInk)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("SONOS VOL", TeGrid, 10, FontWeight.Bold, spacing = 1f)
            Spacer(Modifier.width(10.dp))
            Label(
                if (volume >= 0) volume.toString().padStart(3, '0') else "---",
                TeOrange, 14, FontWeight.Black
            )
            Spacer(Modifier.weight(1f))
            Key("-", onVolumeDown, fill = TePaper, ink = TeInk)
            Spacer(Modifier.width(8.dp))
            Key("+", onVolumeUp, fill = TePaper, ink = TeInk)
        }
        Spacer(Modifier.height(10.dp))
        SessionReadout(session.startedAt)
    }
}

/** 1 Hz uptime/TX counter; only this text recomposes, only while visible. */
@Composable
private fun SessionReadout(startedAt: Long) {
    var text by remember { mutableStateOf(readout(startedAt)) }
    LaunchedEffect(startedAt) {
        while (true) {
            text = readout(startedAt)
            delay(1000)
        }
    }
    Label(text, TeOrange, 10, spacing = 1f)
}

private fun readout(startedAt: Long): String {
    val up = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
    val mb = RelayState.txBytes / 1_000_000.0
    // Locale.ROOT: no "0,5" decimals or non-Latin digits on a TE readout.
    return String.format(
        Locale.ROOT, "UPTIME %02d:%02d:%02d / TX %.1f MB",
        up / 3600, (up % 3600) / 60, up % 60, mb
    )
}

@Composable
private fun PrefRow(label: String, caption: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(TePaper, Corner)
            .border(1.5.dp, TeInk, Corner)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onClick = onToggle
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Label(label, TeInk, 12, FontWeight.Bold, spacing = 1f)
            Label(caption, TeDim, 9)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(20.dp)
                .border(1.5.dp, TeInk)
                .padding(4.dp)
        ) {
            if (checked) Box(Modifier.fillMaxSize().background(TeOrange))
        }
    }
}

@Composable
private fun ScanningLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BlinkingLed(TeOrange, size = 8, onTime = 100, offTime = 400)
        Spacer(Modifier.width(10.dp))
        Label("SCANNING NETWORK", TeDim, 12, FontWeight.Bold, spacing = 1f)
    }
}

/** Hard on/off blink on a low-frequency timer instead of a 60 fps fade. */
@Composable
private fun BlinkingLed(color: Color, size: Int = 8, onTime: Long = 250, offTime: Long = 750) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(onTime)
            on = false
            delay(offTime)
            on = true
        }
    }
    Box(Modifier.size(size.dp).background(if (on) color else color.copy(alpha = 0.15f)))
}

@Composable
private fun EmptyState() {
    Column {
        Label("NO SONOS FOUND", TeInk, 14, FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Label(
            "PHONE AND SPEAKER MUST SHARE A WI-FI NETWORK (NOT A GUEST NETWORK). THEN TAP RESCAN.",
            TeDim, 10, lineHeight = 15
        )
    }
}

@Composable
private fun SpeakerRow(index: Int, speaker: SonosSpeaker, isActiveTarget: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed) TeGrid else TePaper, Corner)
            .border(
                width = if (isActiveTarget) 2.dp else 1.5.dp,
                color = if (isActiveTarget) TeOrange else TeInk,
                shape = Corner
            )
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Label(
            (index + 1).toString().padStart(2, '0'),
            if (isActiveTarget) TeOrange else TeDim,
            13, FontWeight.Bold
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Label(speaker.name.uppercase(), TeInk, 14, FontWeight.Bold)
            if (speaker.detail.isNotBlank()) Label(speaker.detail.uppercase(), TeDim, 10)
        }
        Label(
            if (isActiveTarget) "ACTIVE" else "SEND",
            if (isActiveTarget) TeOrange else TeDim,
            10, FontWeight.Bold
        )
    }
}
