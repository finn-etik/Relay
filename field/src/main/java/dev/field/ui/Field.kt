package dev.field.ui

import android.content.res.Configuration
import android.graphics.Color.TRANSPARENT
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Field Design Language: shared tokens and components.
 *
 * The core palette is fixed by the spec. Each app owns exactly one signal
 * accent (Relay orange, CAL green, GATE blue, REEL red, TALLY amber, DIAL violet, LIST magenta; PAGE takes a
 * deliberately quiet sage), provided through [FieldTheme] and used only for
 * live / active / urgent.
 */

/**
 * The surface tones. [FieldColors.Light] is the spec; Dark is its night skin.
 * [panel] / [onPanel] are the register card: ink on the light body, a raised
 * dark card (not an inverted light one) at night.
 */
class FieldPalette(
    val body: Color, val paper: Color, val ink: Color, val dim: Color, val grid: Color,
    val panel: Color = ink, val onPanel: Color = paper,
)

object FieldColors {
    val Light = FieldPalette(
        body = Color(0xFFDADAD4),
        paper = Color(0xFFF5F5F1),
        ink = Color(0xFF191919),
        dim = Color(0xFF8A8A82),
        grid = Color(0xFFC4C4BC),
    )
    val Dark = FieldPalette(
        body = Color(0xFF121211),
        paper = Color(0xFF1C1C1A),
        ink = Color(0xFFE6E6DF),
        dim = Color(0xFF7D7D76),
        grid = Color(0xFF2C2C29),
        panel = Color(0xFF262623),
        onPanel = Color(0xFFE6E6DF),
    )

    /**
     * The active palette. Snapshot state: everything drawn with the tones
     * below follows a change. Apps that never set it stay on [Light].
     */
    var palette by mutableStateOf(Light)

    val Body: Color get() = palette.body
    val Paper: Color get() = palette.paper
    val Ink: Color get() = palette.ink
    val Dim: Color get() = palette.dim
    val Grid: Color get() = palette.grid
    val Panel: Color get() = palette.panel
    val OnPanel: Color get() = palette.onPanel

    val SignalGreen = Color(0xFF00C853)
    val SignalBlue = Color(0xFF0062FF)
    val SignalRed = Color(0xFFFF0000)
    val SignalAmber = Color(0xFFFFB000)
    val SignalViolet = Color(0xFF6200EA)
    val SignalMagenta = Color(0xFFD6006F)

    /** Blue, red and violet lifted to stay readable as text on the dark body. */
    val SignalBlueOnDark = Color(0xFF5C9DFF)
    val SignalRedOnDark = Color(0xFFFF5A4F)
    val SignalVioletOnDark = Color(0xFFB08CFF)
    val SignalMagentaOnDark = Color(0xFFFF6FB5)
    /** PAGE: a calm accent for long writing sessions, not a signal. */
    val Sage = Color(0xFF4F7A6B)
}

/** The current app's signal accent. */
val LocalAccent = staticCompositionLocalOf { FieldColors.SignalGreen }

@Composable
fun FieldTheme(accent: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccent provides accent, content = content)
}

/** Draw edge to edge with system-bar icons that stay visible on the body: dark icons on the light body, light on [dark]. */
fun ComponentActivity.fieldEdgeToEdge(dark: Boolean = false) {
    val style = if (dark) SystemBarStyle.dark(TRANSPARENT) else SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

fun ComponentActivity.applyFieldPalette(dark: Boolean) {
    FieldColors.palette = if (dark) FieldColors.Dark else FieldColors.Light
    fieldEdgeToEdge(dark)
}

/**
 * setContent for a Field app that follows the system light / dark setting:
 * palette, system-bar icons and accent ([accentOnDark] where the signal
 * colour needs lifting to read on the dark body). The palette is set before
 * the first frame, so a dark start never flashes light.
 */
fun ComponentActivity.fieldContent(accent: Color, accentOnDark: Color = accent, content: @Composable () -> Unit) {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    applyFieldPalette(night)
    setContent {
        val dark = isSystemInDarkTheme()
        LaunchedEffect(dark) { applyFieldPalette(dark) }
        FieldTheme(if (dark) accentOnDark else accent, content)
    }
}

private val KeyShape = RoundedCornerShape(3.dp)
val CardShape = RoundedCornerShape(4.dp)
private val SheetShape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)

// ---------------------------------------------------------------- text

/** All copy goes through this: monospace, no Material. */
@Composable
fun FieldText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Bold,
    color: Color = FieldColors.Ink,
    tracking: TextUnit = 0.sp,
    align: TextAlign? = null,
    lineHeight: TextUnit? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = size,
            fontWeight = weight,
            letterSpacing = tracking,
            color = color,
            textAlign = align ?: TextAlign.Unspecified,
            lineHeight = lineHeight ?: TextUnit.Unspecified,
        ),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    FieldText(text, 10.sp, modifier, color = FieldColors.Ink, tracking = 2.sp)
}

// ---------------------------------------------------------------- LED

enum class LedMode { Off, Solid, Slow, Fast }

/** Hard on/off blink, never a fade. Slow = 250/750 ms, fast = 100/400 ms. Off = 15% accent. */
@Composable
fun BlinkingLed(mode: LedMode, size: Dp = 8.dp, color: Color = LocalAccent.current) {
    var on by remember(mode) { mutableStateOf(mode != LedMode.Off) }
    LaunchedEffect(mode) {
        when (mode) {
            LedMode.Off -> on = false
            LedMode.Solid -> on = true
            LedMode.Slow -> while (true) { on = true; delay(250); on = false; delay(750) }
            LedMode.Fast -> while (true) { on = true; delay(100); on = false; delay(400) }
        }
    }
    Box(Modifier.size(size).background(if (on) color else color.copy(alpha = 0.15f), CircleShape))
}

// ---------------------------------------------------------------- keys

enum class KeyVariant { Paper, Accent, Ink }

/** Paper key, 3 dp radius, no ripple. Press and "active" = 2 dp border. */
@Composable
fun FieldKey(
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    size: TextUnit = 15.sp,
    variant: KeyVariant = KeyVariant.Paper,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when (variant) {
        KeyVariant.Accent -> accent
        KeyVariant.Ink -> FieldColors.Ink
        KeyVariant.Paper -> FieldColors.Paper
    }
    val fg = when {
        !enabled -> FieldColors.Dim
        variant != KeyVariant.Paper -> FieldColors.Paper
        active -> accent
        else -> FieldColors.Ink
    }
    val border = when {
        variant == KeyVariant.Ink -> FieldColors.Paper
        active -> accent
        else -> FieldColors.Ink
    }
    Box(
        modifier
            .height(height)
            .background(if (enabled) bg else FieldColors.Paper, KeyShape)
            .border(if ((pressed && enabled) || active) 2.dp else 1.5.dp, if (enabled) border else FieldColors.Dim, KeyShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        FieldText(label, size, color = fg, tracking = 1.sp, maxLines = 1)
    }
}

/** Whole row toggles: label, caption, 20 dp square box with 4 dp inset accent fill. */
@Composable
fun FieldToggleRow(label: String, caption: String, checked: Boolean, onToggle: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(FieldColors.Paper, CardShape)
            .border(1.5.dp, FieldColors.Ink, CardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Checkbox,
                onClick = onToggle,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            FieldText(label, 12.sp, tracking = 1.sp)
            FieldText(caption, 9.sp, color = FieldColors.Dim)
        }
        Box(Modifier.size(20.dp).border(1.5.dp, FieldColors.Ink)) {
            if (checked) Box(Modifier.fillMaxSize().padding(4.dp).background(accent))
        }
    }
}

/**
 * A section that starts closed: for what is needed now and then (settings,
 * management), so it does not sit between the user and the main controls.
 * [caption] should say the current state, so opening it is rarely needed.
 */
@Composable
fun ColumnScope.FieldFold(title: String, caption: String = "", content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(FieldColors.Paper, CardShape)
            .border(1.5.dp, FieldColors.Ink, CardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { open = !open },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            FieldText(title, 12.sp, tracking = 1.sp)
            if (caption.isNotEmpty()) FieldText(caption, 9.sp, color = FieldColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FieldText(if (open) "CLOSE" else "OPEN", 10.sp, color = FieldColors.Dim, tracking = 1.sp)
    }
    if (open) content()
}

// ---------------------------------------------------------------- surfaces

/** 26 dp dot grid, positions computed once per size. */
fun Modifier.fieldGrid(): Modifier = drawWithCache {
    val pitch = 26.dp.toPx()
    val radius = 1.dp.toPx()
    val width = size.width
    val height = size.height
    val dots = ArrayList<Offset>()
    var x = 0f
    while (x <= width) {
        var y = 0f
        while (y <= height) {
            dots.add(Offset(x, y))
            y += pitch
        }
        x += pitch
    }
    onDrawBehind { for (c in dots) drawCircle(FieldColors.Grid, radius = radius, center = c) }
}

/**
 * Screen scaffold: body colour, dot grid, system-bar and keyboard insets,
 * and one column at most 420 dp wide. [scroll] = false for screens that
 * manage their own scrolling.
 *
 * [sheet] is the place for details: the thing the user just tapped (a
 * calendar entry, a take, a task). It is drawn over the bottom of the
 * screen, so opening it never moves the page; the page behind stays
 * scrollable and tappable, and is padded so its last rows can still be
 * scrolled clear of the sheet. Back closes it ([onDismissSheet]).
 */
@Composable
fun FieldScreen(
    scroll: Boolean = true,
    sheet: (@Composable ColumnScope.() -> Unit)? = null,
    onDismissSheet: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = sheet != null, onBack = onDismissSheet)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(FieldColors.Body)
            .fieldGrid()
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val sheetLimit = maxHeight * 0.62f
        var sheetHeightPx by remember { mutableIntStateOf(0) }
        val reserve = if (sheet != null) with(LocalDensity.current) { sheetHeightPx.toDp() } else 0.dp

        val base = Modifier.fillMaxSize().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
        Column(base.padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().let { if (scroll) it else it.weight(1f) },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
            if (scroll) Spacer(Modifier.height(reserve))
        }

        if (sheet != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 468.dp)
                    .fillMaxWidth()
                    .heightIn(max = sheetLimit)
                    .onSizeChanged { sheetHeightPx = it.height }
                    .background(FieldColors.Panel, SheetShape)
                    // Taps on the sheet's own background must not reach the page under it.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
            ) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(LocalAccent.current))
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                    content = sheet,
                )
            }
        }
    }
}

/** App name, status LED + word, 2 dp rule, and a dim readout line underneath. */
@Composable
fun StatusHeader(title: String, led: LedMode, status: String, left: String, right: String = "") {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FieldText(title, 22.sp, weight = FontWeight.Black, tracking = 2.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BlinkingLed(led, size = 10.dp)
                FieldText(status, 11.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(FieldColors.Ink))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            FieldText(left, 10.sp, Modifier.weight(1f), color = FieldColors.Dim, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (right.isNotEmpty()) FieldText(right, 10.sp, color = FieldColors.Dim, tracking = 1.sp, maxLines = 1)
        }
    }
}

/** Ink "register" card: the instrument's main readout. */
@Composable
fun RegisterPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(FieldColors.Panel, CardShape).padding(16.dp), content = content)
}

/** Paper card with a 1.5 dp ink border. */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    borderColor: Color = FieldColors.Ink,
    borderWidth: Dp = 1.5.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().background(FieldColors.Paper, CardShape).border(borderWidth, borderColor, CardShape).padding(16.dp),
        content = content,
    )
}

@Composable
fun EmptyCard(title: String, caption: String) {
    PaperCard {
        FieldText(title, 14.sp)
        FieldText(caption, 10.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim, lineHeight = 15.sp)
    }
}

/** List row: numbered square, body, optional trailing readout. Accent border when selected. */
@Composable
fun FieldRow(
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String = "",
    content: @Composable () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(FieldColors.Paper, CardShape)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) accent else FieldColors.Ink, CardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp).border(1.5.dp, FieldColors.Ink), contentAlignment = Alignment.Center) {
            FieldText("$index", 10.sp)
        }
        Box(Modifier.weight(1f)) { content() }
        if (trailing.isNotEmpty()) {
            FieldText(trailing, 10.sp, Modifier.widthIn(max = 150.dp), color = FieldColors.Dim, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Task row: a tick box with its own tap target (ticking must not open the
 * row), body, optional trailing readout. Accent border when selected.
 */
@Composable
fun FieldCheckRow(
    checked: Boolean,
    onCheck: () -> Unit,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String = "",
    content: @Composable () -> Unit,
) {
    val accent = LocalAccent.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(FieldColors.Paper, CardShape)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) accent else FieldColors.Ink, CardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(start = 2.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 44 dp square: a finger-sized target around the 20 dp box.
        Box(
            Modifier
                .size(44.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Checkbox,
                    onClick = onCheck,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(20.dp).border(1.5.dp, FieldColors.Ink), contentAlignment = Alignment.Center) {
                if (checked) Box(Modifier.size(12.dp).background(accent))
            }
        }
        Box(Modifier.weight(1f).padding(vertical = 10.dp)) { content() }
        if (trailing.isNotEmpty()) {
            FieldText(
                trailing, 10.sp, Modifier.padding(start = 8.dp).widthIn(max = 130.dp),
                color = FieldColors.Dim, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Input row: paper card, ink border, monospace caps-friendly text field. */
@Composable
fun FieldInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = FieldColors.Ink,
    )
    Box(
        modifier
            .height(height)
            .background(FieldColors.Paper, KeyShape)
            .border(1.5.dp, FieldColors.Ink, KeyShape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) FieldText(placeholder, 12.sp, color = FieldColors.Dim, tracking = 1.sp, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = style,
            singleLine = true,
            cursorBrush = SolidColor(LocalAccent.current),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onDone() }),
        )
    }
}

/** Bottom line naming the skin, per spec. */
@Composable
fun SkinLine(text: String) {
    FieldText(text, 10.sp, Modifier.padding(top = 6.dp), color = FieldColors.Dim, tracking = 1.sp)
}
