package dev.field.page

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.field.ui.BlinkingLed
import dev.field.ui.FieldColors
import dev.field.ui.FieldKey
import dev.field.ui.FieldText
import dev.field.ui.Fmt
import dev.field.ui.KeyVariant
import dev.field.ui.LedMode
import dev.field.ui.LocalAccent
import dev.field.ui.PaperCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class Panel { NONE, SPRINT, STYLE, MORE }

/** Where the typewriter line sits, as a fraction of the visible height. */
private const val TYPEWRITER_ANCHOR = 0.36f

private fun issueColor(kind: IssueKind): Color = when (kind) {
    IssueKind.LONG -> FieldColors.SignalRed
    IssueKind.FILLER -> FieldColors.SignalAmber
    IssueKind.ADVERB -> FieldColors.SignalBlue
    IssueKind.PASSIVE -> FieldColors.SignalGreen
}

/**
 * Paints the raw Markdown: syntax marks dim, emphasis shown, style-check
 * washes behind, and everything outside [focus] faded. The text itself is
 * untouched, so offsets map one to one. Ranges are clamped because style
 * issues are computed a moment behind the typing.
 */
private fun decorate(
    text: String,
    md: List<MdSpan>,
    issues: List<Issue>,
    focus: Span?,
    ink: Color,
    dim: Color,
    grid: Color,
): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    val n = text.length
    fun add(style: SpanStyle, start: Int, end: Int) {
        val a = start.coerceIn(0, n)
        val z = end.coerceIn(0, n)
        if (a < z) builder.addStyle(style, a, z)
    }
    for (i in issues) {
        val alpha = if (i.kind == IssueKind.LONG) 0.14f else 0.34f
        add(SpanStyle(background = issueColor(i.kind).copy(alpha = alpha)), i.start, i.end)
    }
    for (m in md) {
        val style = when (m.kind) {
            MdKind.HEADING, MdKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
            MdKind.ITALIC, MdKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic)
            MdKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            MdKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = grid.copy(alpha = 0.6f))
            MdKind.LINK -> SpanStyle(textDecoration = TextDecoration.Underline)
            MdKind.MARK -> SpanStyle(color = dim)
        }
        add(style, m.start, m.end)
    }
    if (focus != null) {
        val faded = SpanStyle(color = ink.copy(alpha = 0.26f))
        add(faded, 0, focus.start)
        add(faded, focus.end, n)
    }
    return builder.toAnnotatedString()
}

@Composable
private fun Tool(label: String, width: Int = 60, active: Boolean = false, onPress: () -> Unit) {
    FieldKey(label, onPress, Modifier.width(width.dp), height = 36.dp, size = 10.sp, active = active)
}

/** Opens a note, or says so plainly if its file cannot be read (it is never opened blank and overwritten). */
@Composable
fun Editor(id: String, state: PageState, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val text = remember(id) { PageStore.read(context, id) }
    if (text != null) {
        Sheet(id, text, state, onClose)
    } else {
        BackHandler { onClose() }
        Column(
            Modifier.fillMaxSize().background(FieldColors.Paper).systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FieldText("CANNOT OPEN THIS NOTE", 14.sp)
            FieldText("THE FILE WAS MOVED OR DELETED, OR THE FOLDER IS NO LONGER AVAILABLE.", 10.sp, color = FieldColors.Dim, lineHeight = 15.sp)
            FieldKey("LIB", onClose, Modifier.width(72.dp), height = 40.dp, size = 11.sp)
        }
    }
}

/** The writing screen: one note, autosaved, with focus, typewriter, style check and sprints. */
@Composable
private fun Sheet(id: String, initial: String, state: PageState, onClose: () -> Unit) {
    val activity = LocalContext.current
    val context = activity.applicationContext
    val settings = state.settings
    val goals = state.goals
    val sprint = goals.sprint
    val accent = LocalAccent.current
    val density = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current

    var field by remember(id) { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var words by remember(id) { mutableIntStateOf(Text.words(field.text)) }
    var zen by rememberSaveable { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    var confirmDelete by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val title = remember(field.text) { Text.title(field.text) }

    fun change(new: TextFieldValue) {
        var next = new
        if (new.text != field.text) {
            if (new.selection.collapsed) {
                Text.autoList(field.text, new.text, new.selection.start)?.let {
                    next = TextFieldValue(it.text, TextRange(it.selStart, it.selEnd))
                }
            }
            val count = Text.words(next.text)
            if (count != words) {
                val delta = count - words
                words = count
                PageStore.update(context) { it.copy(goals = it.goals.record(today(), delta, System.currentTimeMillis())) }
            }
            PageStore.stage(id, next.text)
        }
        field = next
    }

    fun applyEdit(edit: Edit) = change(TextFieldValue(edit.text, TextRange(edit.selStart, edit.selEnd)))

    fun set(edit: (Settings) -> Settings) = PageStore.update(context) { it.copy(settings = edit(it.settings)) }

    fun close() {
        PageStore.settle(context, id, field.text) // save; drop an empty sheet; name the file
        onClose()
    }

    // Autosave shortly after the last keystroke (and in MainActivity.onStop).
    LaunchedEffect(field.text) {
        delay(600)
        withContext(Dispatchers.IO) { PageStore.flush(context) }
    }

    // Sprint clock: ticks on the second and logs the sprint when its time is up.
    LaunchedEffect(sprint != null) {
        if (sprint != null) {
            while (true) {
                now = System.currentTimeMillis()
                PageStore.update(context) { it.copy(goals = it.goals.finishSprint(now)) }
                delay(1000 - now % 1000)
            }
        }
    }

    BackHandler {
        when {
            zen -> zen = false
            panel != Panel.NONE -> panel = Panel.NONE
            else -> close()
        }
    }

    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) PageStore.writeUri(context, uri, field.text)
    }
    val saveHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) PageStore.writeUri(context, uri, Html.document(title, field.text))
    }
    val saveTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) PageStore.writeUri(context, uri, Html.plain(field.text))
    }

    // ---- what the text looks like ----

    val currentText = field.text
    val styleOn = settings.style
    val md = remember(currentText) { Markdown.spans(currentText) }
    val issues by produceState(emptyList<Issue>(), currentText, styleOn) {
        value = if (!styleOn) {
            emptyList()
        } else {
            delay(300) // let a burst of typing finish
            withContext(Dispatchers.Default) { Style.check(currentText) }
        }
    }
    val cursor = field.selection.start
    val focus = remember(currentText, cursor, settings.focus) {
        when (settings.focus) {
            FocusMode.OFF -> null
            FocusMode.SENTENCE -> Text.sentence(currentText, cursor)
            FocusMode.PARAGRAPH -> Text.paragraph(currentText, cursor)
        }
    }
    val ink = FieldColors.Ink
    val dim = FieldColors.Dim
    val grid = FieldColors.Grid
    val transformation = remember(md, issues, focus, ink, dim, grid) {
        VisualTransformation { original ->
            TransformedText(decorate(original.text, md, issues, focus, ink, dim, grid), OffsetMapping.Identity)
        }
    }
    val fontSize = when (settings.size) {
        0 -> 15
        2 -> 20
        else -> 17
    }
    val textStyle = TextStyle(
        fontFamily = when (settings.face) {
            Face.MONO -> FontFamily.Monospace
            Face.SERIF -> FontFamily.Serif
            Face.SANS -> FontFamily.SansSerif
        },
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.6f).sp,
        color = ink,
    )

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(id) { runCatching { focusRequester.requestFocus() } }

    Box(Modifier.fillMaxSize().background(FieldColors.Paper).systemBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {

            if (!zen) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    FieldKey("LIB", { close() }, Modifier.width(52.dp), height = 32.dp, size = 10.sp)
                    FieldText(
                        title.ifEmpty { "UNTITLED" }.uppercase(), 10.sp, Modifier.weight(1f),
                        color = dim, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (sprint != null) {
                        BlinkingLed(LedMode.Slow)
                        FieldText(Fmt.mmss(goals.sprintRemainingMs(now)) + " +" + sprint.words, 11.sp, maxLines = 1)
                    } else {
                        if (goals.target > 0 && goals.today(today()) >= goals.target) BlinkingLed(LedMode.Solid)
                        FieldText("$words W", 11.sp, maxLines = 1)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.5.dp).background(ink))
            }

            // ---- the sheet ----
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val viewport = constraints.maxHeight
                val typewriter = settings.typewriter
                val anchor = (viewport * TYPEWRITER_ANCHOR).toInt()
                val linePx = with(density) { textStyle.lineHeight.toPx() }.toInt()
                val edge = with(density) { 20.dp.toPx() }.toInt()
                // Room above and below so the first and the last line can both sit on the anchor.
                val top = if (typewriter) anchor else edge
                val bottom = if (typewriter) maxOf(edge, viewport - anchor - linePx) else edge

                val scroll = rememberScrollState()
                var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                LaunchedEffect(cursor, layout, typewriter, viewport) {
                    val l = layout
                    if (typewriter && l != null) {
                        val offset = cursor.coerceIn(0, l.layoutInput.text.length)
                        // The text starts `top` (= anchor) below the scroll origin, so the
                        // cursor line lands on the anchor when we scroll by its own top.
                        scroll.animateScrollTo(l.getLineTop(l.getLineForOffset(offset)).toInt())
                    }
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            runCatching { focusRequester.requestFocus() }
                            keyboard?.show()
                        }
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(with(density) { top.toDp() }))
                    Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                        if (field.text.isEmpty()) {
                            FieldText("START WRITING", 12.sp, color = dim, tracking = 2.sp)
                        }
                        BasicTextField(
                            value = field,
                            onValueChange = { change(it) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            textStyle = textStyle,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            visualTransformation = transformation,
                            onTextLayout = { layout = it },
                            cursorBrush = SolidColor(accent),
                        )
                    }
                    Spacer(Modifier.height(with(density) { bottom.toDp() }))
                }
            }

            if (!zen) {
                when (panel) {
                    Panel.NONE -> {}

                    Panel.SPRINT -> PaperCard(Modifier.padding(horizontal = 16.dp)) {
                        if (sprint == null) {
                            FieldText("TIMED SPRINT / MINUTES", 10.sp, color = dim, tracking = 1.sp)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Goals.SPRINTS.forEach { minutes ->
                                    FieldKey(
                                        Fmt.pad2(minutes),
                                        {
                                            PageStore.update(context) { it.copy(goals = it.goals.startSprint(System.currentTimeMillis(), minutes)) }
                                            panel = Panel.NONE
                                        },
                                        Modifier.weight(1f), height = 40.dp, size = 12.sp,
                                    )
                                }
                            }
                            goals.log.firstOrNull()?.let {
                                FieldText("LAST: ${it.minutes} MIN / +${it.words} W", 10.sp, Modifier.padding(top = 8.dp), color = accent, tracking = 1.sp)
                            }
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FieldText("SPRINT ${sprint.minutes} MIN / +${sprint.words} W", 12.sp, Modifier.weight(1f), tracking = 1.sp)
                                FieldKey(
                                    "END",
                                    { PageStore.update(context) { it.copy(goals = it.goals.cancelSprint()) } },
                                    Modifier.width(64.dp), height = 32.dp, size = 10.sp,
                                )
                            }
                        }
                    }

                    Panel.STYLE -> PaperCard(Modifier.padding(horizontal = 16.dp)) {
                        listOf(
                            IssueKind.FILLER to "FILLER WORDS",
                            IssueKind.ADVERB to "ADVERBS",
                            IssueKind.PASSIVE to "PASSIVE VOICE",
                            IssueKind.LONG to "SENTENCES OVER ${Style.LONG_WORDS} WORDS",
                        ).forEach { (kind, label) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(Modifier.size(12.dp).background(issueColor(kind).copy(alpha = 0.6f)))
                                FieldText(label, 11.sp, Modifier.weight(1f), tracking = 1.sp)
                                FieldText(Fmt.pad3(Style.count(issues, kind)), 11.sp)
                            }
                        }
                        FieldText("ENGLISH WORD LISTS, ON THIS DEVICE. HINTS, NOT RULES.", 9.sp, Modifier.padding(top = 6.dp), color = dim)
                    }

                    Panel.MORE -> PaperCard(Modifier.padding(horizontal = 16.dp)) {
                        FieldText(
                            "$words W / ${field.text.length} CH / ${Text.readingMinutes(words)} MIN READ",
                            10.sp, color = dim, tracking = 1.sp,
                        )
                        val name = Text.fileName(title)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldKey("SHARE", { Export.share(activity, title, field.text) }, Modifier.weight(1f), height = 40.dp, size = 11.sp)
                            FieldKey(".MD", { saveMd.launch("$name.md") }, Modifier.weight(1f), height = 40.dp, size = 11.sp)
                            FieldKey(".HTML", { saveHtml.launch("$name.html") }, Modifier.weight(1f), height = 40.dp, size = 11.sp)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldKey(".TXT", { saveTxt.launch("$name.txt") }, Modifier.weight(1f), height = 40.dp, size = 11.sp)
                            FieldKey("PDF", { Export.print(activity, title, Html.document(title, field.text)) }, Modifier.weight(1f), height = 40.dp, size = 11.sp)
                            FieldKey(
                                if (confirmDelete) "SURE?" else "DELETE",
                                {
                                    if (confirmDelete) {
                                        PageStore.delete(context, id)
                                        onClose()
                                    } else {
                                        confirmDelete = true
                                    }
                                },
                                Modifier.weight(1f), height = 40.dp, size = 11.sp,
                                variant = if (confirmDelete) KeyVariant.Ink else KeyVariant.Paper,
                            )
                        }
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Tool("ZEN") {
                        panel = Panel.NONE
                        zen = true
                    }
                    Tool(
                        when (settings.focus) {
                            FocusMode.OFF -> "FOCUS"
                            FocusMode.SENTENCE -> "SENTENCE"
                            FocusMode.PARAGRAPH -> "PARAGRAPH"
                        },
                        width = 84, active = settings.focus != FocusMode.OFF,
                    ) {
                        set { it.copy(focus = FocusMode.entries[(it.focus.ordinal + 1) % FocusMode.entries.size]) }
                    }
                    Tool("TYPEWRITER", width = 92, active = settings.typewriter) { set { it.copy(typewriter = !it.typewriter) } }
                    Tool("STYLE", active = settings.style) {
                        panel = if (settings.style) Panel.NONE else Panel.STYLE
                        set { it.copy(style = !it.style) }
                    }
                    Tool("SPRINT", active = sprint != null) { panel = if (panel == Panel.SPRINT) Panel.NONE else Panel.SPRINT }
                    Tool("H", width = 40) { applyEdit(Text.cycleHeading(field.text, field.selection.start)) }
                    Tool("B", width = 40) { applyEdit(Text.wrap(field.text, field.selection.min, field.selection.max, "**")) }
                    Tool("I", width = 40) { applyEdit(Text.wrap(field.text, field.selection.min, field.selection.max, "*")) }
                    Tool("LIST", width = 52) { applyEdit(Text.togglePrefix(field.text, field.selection.start, "- ")) }
                    Tool("QUOTE", width = 56) { applyEdit(Text.togglePrefix(field.text, field.selection.start, "> ")) }
                    Tool("MORE", active = panel == Panel.MORE) {
                        confirmDelete = false
                        panel = if (panel == Panel.MORE) Panel.NONE else Panel.MORE
                    }
                }
            }
        }

        // Zen: nothing but the sheet. This corner lamp (blinking during a sprint) brings the keys back.
        if (zen) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { zen = false },
                contentAlignment = Alignment.Center,
            ) {
                BlinkingLed(if (sprint != null) LedMode.Slow else LedMode.Off)
            }
        }
    }
}
