package dev.field.lore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.field.lore.LoreStore.Engine
import dev.field.lore.LoreStore.State
import dev.field.ui.CardShape
import dev.field.ui.EmptyCard
import dev.field.ui.FieldColors
import dev.field.ui.FieldFold
import dev.field.ui.FieldInput
import dev.field.ui.FieldKey
import dev.field.ui.FieldProse
import dev.field.ui.FieldRow
import dev.field.ui.FieldScreen
import dev.field.ui.FieldText
import dev.field.ui.FieldToggleRow
import dev.field.ui.KeyVariant
import dev.field.ui.Kit
import dev.field.ui.LedMode
import dev.field.ui.LocalAccent
import dev.field.ui.PaperCard
import dev.field.ui.SectionHeader
import dev.field.ui.StatusHeader
import dev.field.ui.fieldContent
import kotlinx.coroutines.delay

/**
 * LORE: an assistant that runs on the phone, in the Field Design Language.
 * Skin: signal cyan.
 *
 * A small language model answers; before it does, LORE looks the question up
 * in the user's notes folder and in offline knowledge packs (Wikipedia,
 * Wikibooks) and hands the model the passages it found, so answers can cite
 * where they come from. The screen shows LoreStore's state and sends it
 * commands; see LoreStore, Ask and Zim.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LoreStore.load(applicationContext)
        if (savedInstanceState == null) receive(intent) // not again after a rotation
        fieldContent(FieldColors.SignalCyan, FieldColors.SignalCyanOnDark) { LoreApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    override fun onResume() {
        super.onResume()
        LoreStore.refresh()
    }

    /** Text shared to LORE (from any app, or another kit app) lands in the question field. */
    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) incoming.value = text.take(4000)
    }

    companion object {
        /** Shared text waiting to be put into the question field. */
        val incoming = mutableStateOf<String?>(null)
    }
}

@Composable
fun LoreApp() {
    val state by LoreStore.state.collectAsState()
    var inLibrary by rememberSaveable { mutableStateOf(false) }

    val reading = state.reading
    when {
        reading != null -> ReaderScreen(reading)
        inLibrary -> LibraryScreen(state) { inLibrary = false }
        else -> ChatScreen(state) { inLibrary = true }
    }
}

// --------------------------------------------------------------------- chat

@Composable
private fun ChatScreen(state: State, onLibrary: () -> Unit) {
    val chat = state.chat
    var draft by rememberSaveable { mutableStateOf("") }
    // What the sheet shows: "", "chats", "turn:<id>" or "source:<turn id>:<index>".
    var sheet by rememberSaveable { mutableStateOf("") }

    val shared = MainActivity.incoming.value
    LaunchedEffect(shared) {
        if (shared != null) {
            draft = shared
            MainActivity.incoming.value = null
        }
    }

    val scroll = rememberScrollState()
    val last = chat?.turns?.lastOrNull()
    // A new question jumps to the end; a growing answer is followed only while the reader is at the end.
    // (A frame is awaited first, so the new text has been measured and the end is where it now is.)
    LaunchedEffect(chat?.id, chat?.turns?.size) {
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(last?.answer?.length, last?.stage) {
        withFrameNanos { }
        if (last != null && scroll.maxValue - scroll.value < 600) scroll.scrollTo(scroll.maxValue)
    }

    val sheetTurn = chat?.turns?.firstOrNull { sheet.substringAfter(':').substringBefore(':') == it.id.toString() }
    val sheetSource = sheetTurn?.sources?.getOrNull(sheet.substringAfterLast(':').toIntOrNull() ?: -1)
    val content = when {
        sheet == "chats" -> sheetOf { ChatsSheet(state) { sheet = "" } }
        sheet.startsWith("turn:") && sheetTurn != null -> sheetOf { TurnSheet(sheetTurn, sheetTurn == last && !state.busy) { sheet = "" } }
        sheet.startsWith("source:") && sheetSource != null -> sheetOf { SourceSheet(sheetSource) }
        else -> null
    }

    FieldScreen(scroll = false, sheet = content, onDismissSheet = { sheet = "" }) {
        val (led, word) = status(state, last)
        StatusHeader(
            "LORE", led, word,
            left = state.notice.ifEmpty { if (state.ready) state.model.name + state.engineNote.takeIf { state.engine == Engine.READY }?.let { " · $it" }.orEmpty() else "NO MODEL" },
            right = "ON DEVICE",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey("NEW", { LoreStore.newChat(); sheet = "" }, Modifier.weight(1f), height = 40.dp, size = 11.sp, enabled = chat != null)
            FieldKey("CHATS", { sheet = if (sheet == "chats") "" else "chats" }, Modifier.weight(1f), height = 40.dp, size = 11.sp, active = sheet == "chats")
            FieldKey("LIBRARY", onLibrary, Modifier.weight(1f), height = 40.dp, size = 11.sp)
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chat == null) Welcome(state, onLibrary)
            chat?.turns?.forEach { turn ->
                TurnView(turn, state, onMore = { sheet = "turn:${turn.id}" }, onSource = { sheet = "source:${turn.id}:$it" })
            }
        }

        if (state.notesFolder.isNotEmpty() || state.shelf.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.notesFolder.isNotEmpty()) {
                    FieldKey(if (state.useNotes) "NOTES ON" else "NOTES OFF", { LoreStore.setUseNotes(!state.useNotes) }, Modifier.weight(1f), height = 34.dp, size = 10.sp, active = state.useNotes)
                }
                if (state.shelf.isNotEmpty()) {
                    FieldKey(if (state.usePacks) "PACKS ON" else "PACKS OFF", { LoreStore.setUsePacks(!state.usePacks) }, Modifier.weight(1f), height = 34.dp, size = 10.sp, active = state.usePacks)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            FieldInput(draft, { draft = it }, if (state.ready) "ASK" else "GET A MODEL FIRST", Modifier.weight(1f), maxLines = 5)
            if (state.busy) {
                FieldKey("STOP", { LoreStore.stop() }, Modifier.width(84.dp), variant = KeyVariant.Ink)
            } else {
                FieldKey(
                    "ASK",
                    {
                        LoreStore.ask(draft)
                        draft = ""
                        sheet = ""
                    },
                    Modifier.width(84.dp), variant = KeyVariant.Accent, enabled = draft.isNotBlank() && state.ready,
                )
            }
        }
    }
}

private fun status(state: State, last: Turn?): Pair<LedMode, String> = when {
    last?.stage == Stage.SEARCHING && state.engine == Engine.LOADING -> LedMode.Slow to "LOADING"
    last?.stage == Stage.SEARCHING -> LedMode.Slow to "SEARCHING"
    last?.stage == Stage.WRITING -> LedMode.Fast to "WRITING"
    !state.ready -> LedMode.Off to "NO MODEL"
    state.engine == Engine.LOADING -> LedMode.Slow to "LOADING"
    state.engine == Engine.FAILED -> LedMode.Off to "FAILED"
    state.engine == Engine.READY -> LedMode.Solid to "READY"
    else -> LedMode.Off to "STANDBY"
}

/** The empty chat: what LORE is waiting for, and what it will look things up in. */
@Composable
private fun Welcome(state: State, onLibrary: () -> Unit) {
    if (!state.ready) {
        val fetching = state.transfers.entries.firstOrNull { it.key.startsWith("model:") || it.key == "copy" }
        if (fetching != null) {
            EmptyCard("GETTING THE MODEL", progress(fetching.value) + "\nIT KEEPS DOWNLOADING WHILE THE APP IS CLOSED.")
        } else {
            EmptyCard(
                "NO MODEL YET",
                "LORE ANSWERS WITH A LANGUAGE MODEL THAT RUNS ON THIS PHONE. NOTHING YOU ASK LEAVES IT. " +
                    "GET THE MODEL ONCE, OVER WI-FI, IN THE LIBRARY.",
            )
            FieldKey("OPEN LIBRARY", onLibrary, Modifier.fillMaxWidth(), variant = KeyVariant.Accent)
        }
        return
    }
    val looksIn = listOfNotNull(
        "YOUR NOTES (${Format.count(state.notesPassages)} PASSAGES)".takeIf { state.useNotes && state.hasNotes },
        state.shelf.filter { it.open }.joinToString(", ") { it.name }.takeIf { state.usePacks && state.hasPacks },
    )
    EmptyCard(
        "ASK ANYTHING",
        if (looksIn.isEmpty()) "ANSWERS COME FROM THE MODEL'S MEMORY ALONE, WHICH CAN BE WRONG. ADD WIKIPEDIA OR YOUR NOTES FOLDER IN THE LIBRARY AND IT WILL LOOK THINGS UP AND SAY WHERE THEY ARE FROM."
        else "LOOKS IN " + looksIn.joinToString(" AND ") + " BEFORE IT ANSWERS, AND NAMES ITS SOURCES.",
    )
    if (state.engine == Engine.FAILED) FieldText(state.engineNote, 10.sp, color = LocalAccent.current, lineHeight = 15.sp)
}

@Composable
private fun TurnView(turn: Turn, state: State, onMore: () -> Unit, onSource: (Int) -> Unit) {
    var showSources by rememberSaveable(turn.id) { mutableStateOf(false) }
    PaperCard { FieldText(turn.question, 13.sp, lineHeight = 19.sp) }

    when (turn.stage) {
        Stage.SEARCHING -> FieldText(if (state.engine == Engine.LOADING) "LOADING THE MODEL" else "LOOKING THINGS UP", 10.sp, color = FieldColors.Dim, tracking = 1.sp)
        Stage.WRITING -> if (turn.answer.isEmpty()) {
            FieldText(if (turn.sources.isEmpty()) "THINKING" else "READING ${turn.sources.size} SOURCES", 10.sp, color = FieldColors.Dim, tracking = 1.sp)
        }
        else -> {}
    }
    if (turn.answer.isNotEmpty()) Answer(turn.answer)
    if (turn.stage == Stage.FAILED) FieldText(turn.note.ifEmpty { "FAILED" }, 10.sp, color = LocalAccent.current, tracking = 1.sp, lineHeight = 15.sp)
    if (turn.stage == Stage.STOPPED) FieldText("STOPPED", 10.sp, color = FieldColors.Dim, tracking = 1.sp)
    if (turn.stage == Stage.DONE && turn.sources.isEmpty()) {
        FieldText("FROM THE MODEL'S MEMORY · NO SOURCE", 9.sp, color = FieldColors.Dim, tracking = 1.sp)
    }

    if (!turn.busy) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (turn.sources.isNotEmpty()) {
                FieldKey(
                    "${turn.sources.size} " + if (turn.sources.size == 1) "SOURCE" else "SOURCES",
                    { showSources = !showSources }, Modifier.weight(1f), height = 36.dp, size = 10.sp, active = showSources,
                )
            }
            FieldKey("MORE", onMore, Modifier.weight(1f), height = 36.dp, size = 10.sp)
        }
    }
    if (showSources) {
        turn.sources.forEachIndexed { i, source ->
            FieldRow(i + 1, selected = false, onClick = { onSource(i) }, trailing = if (source.origin == Origin.NOTE) "NOTE" else "PACK") {
                FieldText(source.label, 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** An answer, with the Markdown a model writes turned into headings, lists and code. Text can be selected. */
@Composable
private fun Answer(text: String) {
    val blocks = remember(text) { Markdown.blocks(text) }
    val accent = LocalAccent.current
    val tint = FieldColors.Grid
    SelectionContainer {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (block in blocks) {
                when (block) {
                    is Markdown.Para -> FieldProse(styled(block.spans, accent, tint))
                    is Markdown.Heading -> FieldProse(styled(block.spans, accent, tint), size = 15.sp, weight = FontWeight.Bold)
                    is Markdown.Item -> Row(Modifier.padding(start = (12 * block.depth.coerceAtMost(3)).dp)) {
                        FieldProse(AnnotatedString(block.marker), Modifier.width(26.dp), color = FieldColors.Dim)
                        FieldProse(styled(block.spans, accent, tint), Modifier.weight(1f))
                    }
                    is Markdown.Code -> Box(
                        Modifier.fillMaxWidth().background(FieldColors.Panel, CardShape).horizontalScroll(rememberScrollState()).padding(12.dp),
                    ) {
                        FieldProse(AnnotatedString(block.text), size = 12.sp, color = FieldColors.OnPanel)
                    }
                }
            }
        }
    }
}

private fun styled(spans: List<Markdown.Span>, accent: Color, tint: Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold || span.cite > 0) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            color = if (span.cite > 0) accent else Color.Unspecified,
            background = if (span.code) tint else Color.Unspecified,
        )
        withStyle(style) { append(span.text) }
    }
}

// ------------------------------------------------------------------- sheets

/** Names the type of a sheet's content, so a `when` can choose between sheets and none. */
private fun sheetOf(content: @Composable ColumnScope.() -> Unit): @Composable ColumnScope.() -> Unit = content

@Composable
private fun ColumnScope.ChatsSheet(state: State, onClose: () -> Unit) {
    SheetLabel("CHATS")
    if (state.chats.isEmpty()) SheetNote("NONE YET. THEY ARE KEPT ON THIS PHONE ONLY.")
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.chats.forEachIndexed { i, chat ->
            FieldRow(i + 1, selected = chat.id == state.current, onClick = { LoreStore.openChat(chat.id); onClose() }, trailing = "${chat.turns.size}") {
                FieldText(chat.title, 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val current = state.chat
    if (current != null) DeleteKey("DELETE THE OPEN CHAT", "TAP AGAIN TO DELETE THIS CHAT") { LoreStore.deleteChat(current.id) }
}

/** What can be done with one answer. */
@Composable
private fun ColumnScope.TurnSheet(turn: Turn, canRetry: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var flash by remember(turn.id) { mutableStateOf("") }
    val text = turn.answer
    SheetLabel("ANSWER")
    FieldText(turn.question, 12.sp, Modifier.padding(top = 6.dp), color = FieldColors.OnPanel, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (flash.isNotEmpty()) FieldText(flash, 10.sp, Modifier.padding(top = 6.dp), color = LocalAccent.current, tracking = 1.sp)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldKey("COPY", { copy(context, text); flash = "COPIED" }, Modifier.weight(1f), height = 40.dp, size = 11.sp, enabled = text.isNotEmpty())
        FieldKey("SHARE", { share(context, text) }, Modifier.weight(1f), height = 40.dp, size = 11.sp, enabled = text.isNotEmpty())
    }
    // Kit links: an answer becomes a note in PAGE or a task in LIST.
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldKey(
            "TO PAGE", { if (!Kit.sendText(context, Kit.PAGE, turn.question, text)) flash = "PAGE IS NOT INSTALLED" },
            Modifier.weight(1f), height = 40.dp, size = 11.sp, enabled = text.isNotEmpty(),
        )
        FieldKey(
            "TO LIST", { if (!Kit.sendText(context, Kit.LIST, turn.question, "")) flash = "LIST IS NOT INSTALLED" },
            Modifier.weight(1f), height = 40.dp, size = 11.sp,
        )
    }
    if (canRetry) {
        FieldKey("ASK AGAIN", { LoreStore.askAgain(); onClose() }, Modifier.fillMaxWidth().padding(top = 8.dp), height = 40.dp, size = 11.sp)
    }
}

/** The passage the model was given, as it was given. */
@Composable
private fun ColumnScope.SourceSheet(source: Source) {
    val context = LocalContext.current
    SheetLabel(if (source.origin == Origin.NOTE) "FROM YOUR NOTES" else "FROM A PACK")
    FieldText(source.label, 14.sp, Modifier.padding(top = 6.dp), color = FieldColors.OnPanel, maxLines = 3, overflow = TextOverflow.Ellipsis)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldKey(if (source.origin == Origin.NOTE) "WHOLE NOTE" else "WHOLE ARTICLE", { LoreStore.read(source) }, Modifier.weight(1f), height = 40.dp, size = 11.sp, variant = KeyVariant.Accent)
        FieldKey("COPY", { copy(context, source.text) }, Modifier.weight(1f), height = 40.dp, size = 11.sp)
    }
    FieldProse(AnnotatedString(source.text), Modifier.padding(top = 12.dp), size = 12.sp, color = FieldColors.OnPanel)
}

@Composable
private fun SheetLabel(text: String) = FieldText(text, 10.sp, color = FieldColors.Dim, tracking = 2.sp)

@Composable
private fun SheetNote(text: String) =
    FieldText(text, 10.sp, Modifier.padding(top = 6.dp), weight = FontWeight.Normal, color = FieldColors.OnPanel, lineHeight = 15.sp)

/** Destructive: placed last, plain, and it asks twice. */
@Composable
private fun DeleteKey(label: String, confirm: String, onDelete: () -> Unit) {
    var armed by remember(label) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(4000)
            armed = false
        }
    }
    FieldKey(
        if (armed) confirm else label,
        {
            if (armed) {
                armed = false
                onDelete()
            } else {
                armed = true
            }
        },
        Modifier.fillMaxWidth().padding(top = 12.dp), height = 40.dp, size = 10.sp,
    )
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("LORE", text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

private fun progress(t: LoreStore.Transfer): String = listOf(
    Format.percent(t.done, t.total),
    if (t.total > 0) Format.bytes(t.done) + " OF " + Format.bytes(t.total) else Format.bytes(t.done),
    t.note,
).filter { it.isNotEmpty() }.joinToString(" · ")

// ------------------------------------------------------------------ library

/** Where the model, the knowledge packs and the notes folder are managed. */
@Composable
private fun LibraryScreen(state: State, onBack: () -> Unit) {
    // What the sheet shows: "", "model:<id>", "pack:<id>" or "archive:<key>".
    var sheet by rememberSaveable { mutableStateOf("") }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) LoreStore.addModel(uri) }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) LoreStore.addArchive(uri) }
    val pickNotes = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) LoreStore.setNotesFolder(uri) }

    val openModel = state.models.firstOrNull { sheet == "model:${it.id}" }
    val openPack = Catalog.PACKS.firstOrNull { sheet == "pack:${it.id}" }
    val openArchive = state.shelf.firstOrNull { sheet == "archive:${it.key}" }
    val content = when {
        openModel != null -> sheetOf { ModelSheet(state, openModel) { sheet = "" } }
        openPack != null -> sheetOf { PackSheet(state, openPack) { sheet = "" } }
        openArchive != null -> sheetOf { ArchiveSheet(openArchive) { sheet = "" } }
        else -> null
    }
    BackHandler(enabled = content == null, onBack = onBack)

    FieldScreen(sheet = content, onDismissSheet = { sheet = "" }) {
        StatusHeader(
            "LORE", LedMode.Off, "LIBRARY",
            left = state.notice.ifEmpty { "FREE " + Format.bytes(state.freeBytes) },
            right = "ON DEVICE",
        )
        FieldKey("BACK TO THE CHAT", onBack, Modifier.fillMaxWidth(), height = 40.dp, size = 11.sp)

        SectionHeader("MODEL", Modifier.padding(top = 8.dp))
        state.models.forEachIndexed { i, model ->
            val transfer = state.transfers["model:${model.id}"]
            FieldRow(
                i + 1, selected = model.id == state.modelId && state.ready, onClick = { sheet = "model:${model.id}" },
                trailing = when {
                    transfer != null -> Format.percent(transfer.done, transfer.total)
                    model.id == state.modelId && state.ready -> "IN USE"
                    model.id in state.installed -> "ON PHONE"
                    state.failures.containsKey("model:${model.id}") -> "FAILED"
                    else -> "GET"
                },
            ) {
                Column {
                    FieldText(model.name, 12.sp, tracking = 1.sp)
                    FieldText(model.caption, 9.sp, color = FieldColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        state.transfers["copy"]?.let { FieldText("COPYING " + progress(it), 10.sp, color = FieldColors.Dim, tracking = 1.sp) }

        SectionHeader("KNOWLEDGE PACKS", Modifier.padding(top = 8.dp))
        Catalog.PACKS.forEachIndexed { i, pack ->
            val transfer = state.transfers["pack:${pack.id}"]
            val onShelf = state.shelf.firstOrNull { it.packId == pack.id }
            FieldRow(
                i + 1, selected = onShelf?.open == true, onClick = { sheet = "pack:${pack.id}" },
                trailing = when {
                    transfer != null -> Format.percent(transfer.done, transfer.total)
                    onShelf?.open == true -> "ON SHELF"
                    onShelf != null -> "BROKEN"
                    state.failures.containsKey("pack:${pack.id}") -> "FAILED"
                    else -> "GET"
                },
            ) {
                Column {
                    FieldText(pack.name, 12.sp, tracking = 1.sp)
                    FieldText(onShelf?.detail ?: pack.caption, 9.sp, color = FieldColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        state.shelf.filter { it.packId.isEmpty() }.forEachIndexed { i, archive ->
            FieldRow(Catalog.PACKS.size + i + 1, selected = archive.open, onClick = { sheet = "archive:${archive.key}" }, trailing = if (archive.open) "ON SHELF" else "BROKEN") {
                Column {
                    FieldText(archive.name, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FieldText(archive.detail, 9.sp, color = FieldColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        SectionHeader("NOTES", Modifier.padding(top = 8.dp))
        if (state.notesFolder.isEmpty()) {
            EmptyCard("NO NOTES FOLDER", "CHOOSE THE FOLDER PAGE SAVES YOUR NOTES TO (OR ANY FOLDER OF .MD AND .TXT FILES). LORE READS IT, NEVER WRITES TO IT.")
            FieldKey("CHOOSE THE NOTES FOLDER", { pickNotes.launch(null) }, Modifier.fillMaxWidth(), height = 44.dp, size = 11.sp)
        } else {
            PaperCard {
                FieldText(state.notesName, 13.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FieldText(
                    when {
                        state.notesNote.isNotEmpty() -> state.notesNote
                        state.indexing && state.notesFiles == 0 -> "INDEXING"
                        else -> "${Format.count(state.notesFiles)} NOTES · ${Format.count(state.notesPassages)} PASSAGES INDEXED" + if (state.indexing) " · UPDATING" else ""
                    },
                    10.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim, tracking = 1.sp,
                )
            }
        }

        FieldFold("SETUP", "${state.backend} · " + if (state.metered) "MOBILE DATA ALLOWED" else "WI-FI ONLY") {
            SectionHeader("RUN THE MODEL ON", Modifier.padding(top = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in listOf("AUTO", "GPU", "CPU")) {
                    FieldKey(choice, { LoreStore.setBackend(choice) }, Modifier.weight(1f), height = 40.dp, size = 11.sp, active = state.backend == choice)
                }
            }
            FieldText(
                "AUTO TRIES THE GRAPHICS CHIP (FASTER) AND FALLS BACK TO THE PROCESSOR IF THE PHONE CANNOT RUN THE MODEL THERE.",
                9.sp, color = FieldColors.Dim, lineHeight = 14.sp,
            )
            FieldToggleRow("MOBILE DATA", "ALLOW DOWNLOADS WITHOUT WI-FI", state.metered) { LoreStore.setMetered(!state.metered) }
            FieldKey("ADD A PACK FROM A FILE (.ZIM)", { pickArchive.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), height = 40.dp, size = 10.sp)
            FieldKey("ADD A MODEL FROM A FILE (.LITERTLM)", { pickModel.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), height = 40.dp, size = 10.sp)
            if (state.notesFolder.isNotEmpty()) {
                FieldKey("CHOOSE ANOTHER NOTES FOLDER", { pickNotes.launch(null) }, Modifier.fillMaxWidth(), height = 40.dp, size = 10.sp)
                DeleteKey("DISCONNECT THE NOTES FOLDER", "TAP AGAIN TO DISCONNECT (THE NOTES STAY)") { LoreStore.forgetNotes() }
            }
        }
    }
}

@Composable
private fun ColumnScope.ModelSheet(state: State, model: Model, onClose: () -> Unit) {
    val key = "model:${model.id}"
    val transfer = state.transfers[key]
    val installed = model.id in state.installed
    val inUse = installed && model.id == state.modelId
    SheetLabel("MODEL")
    FieldText(model.name, 16.sp, Modifier.padding(top = 6.dp), color = FieldColors.OnPanel)
    SheetNote(
        when {
            transfer != null -> progress(transfer)
            inUse -> "IN USE" + if (state.engine == Engine.READY) " · RUNNING ON THE ${state.engineNote}" else ""
            installed -> "ON THIS PHONE"
            else -> model.caption
        }
    )
    state.failures[key]?.let { FieldText(it, 10.sp, Modifier.padding(top = 6.dp), color = LocalAccent.current, tracking = 1.sp, lineHeight = 15.sp) }
    if (inUse && state.engine == Engine.FAILED) {
        FieldText(state.engineNote, 10.sp, Modifier.padding(top = 6.dp), color = LocalAccent.current, lineHeight = 15.sp)
    }
    when {
        transfer != null -> FieldKey("CANCEL THE DOWNLOAD", { LoreStore.cancel(key) }, Modifier.fillMaxWidth().padding(top = 12.dp), height = 44.dp, size = 11.sp)
        installed -> {
            if (!inUse) {
                FieldKey("USE THIS MODEL", { LoreStore.useModel(model.id); onClose() }, Modifier.fillMaxWidth().padding(top = 12.dp), height = 44.dp, size = 11.sp, variant = KeyVariant.Accent)
            }
            DeleteKey("DELETE FROM THIS PHONE", "TAP AGAIN TO DELETE THE MODEL") { LoreStore.deleteModel(model.id); onClose() }
        }
        else -> {
            FieldKey("DOWNLOAD", { LoreStore.getModel(model.id) }, Modifier.fillMaxWidth().padding(top = 12.dp), height = 44.dp, size = 11.sp, variant = KeyVariant.Accent)
            SheetNote(if (state.metered) "MOBILE DATA IS ALLOWED (SETUP)." else "DOWNLOADS OVER WI-FI ONLY (CHANGE UNDER SETUP). IT CONTINUES IN THE BACKGROUND.")
        }
    }
}

@Composable
private fun ColumnScope.PackSheet(state: State, pack: Pack, onClose: () -> Unit) {
    val key = "pack:${pack.id}"
    val transfer = state.transfers[key]
    val onShelf = state.shelf.firstOrNull { it.packId == pack.id }
    val remote = state.remotes[pack.id]
    SheetLabel("KNOWLEDGE PACK")
    FieldText(pack.name, 16.sp, Modifier.padding(top = 6.dp), color = FieldColors.OnPanel)
    SheetNote(
        when {
            transfer != null -> progress(transfer)
            onShelf != null -> onShelf.detail
            else -> pack.caption + ". PUBLISHED BY KIWIX; LORE READS IT OFFLINE."
        }
    )
    state.failures[key]?.let { FieldText(it, 10.sp, Modifier.padding(top = 6.dp), color = LocalAccent.current, tracking = 1.sp, lineHeight = 15.sp) }
    when {
        transfer != null -> FieldKey("CANCEL THE DOWNLOAD", { LoreStore.cancel(key) }, Modifier.fillMaxWidth().padding(top = 12.dp), height = 44.dp, size = 11.sp)
        onShelf != null -> DeleteKey("DELETE FROM THIS PHONE", "TAP AGAIN TO DELETE THE PACK") { LoreStore.deletePack(pack.id); onClose() }
        remote == null -> {
            // Two steps on purpose: first see how large the current edition is, then decide.
            FieldKey(
                if (pack.id in state.looking) "LOOKING" else "FIND THE CURRENT EDITION",
                { LoreStore.findPack(pack.id) }, Modifier.fillMaxWidth().padding(top = 12.dp), height = 44.dp, size = 11.sp,
                variant = KeyVariant.Accent, enabled = pack.id !in state.looking,
            )
        }
        else -> {
            SheetNote(remote.file.removeSuffix(".zim").uppercase() + (if (remote.size.isNotEmpty()) " · ${remote.size}" else "") + " · FREE ON THIS PHONE: " + Format.bytes(state.freeBytes))
            FieldKey(
                "DOWNLOAD" + if (remote.size.isNotEmpty()) " ${remote.size}" else "",
                { LoreStore.getPack(pack.id) }, Modifier.fillMaxWidth().padding(top = 12.dp), height = 44.dp, size = 11.sp, variant = KeyVariant.Accent,
            )
            SheetNote(if (state.metered) "MOBILE DATA IS ALLOWED (SETUP)." else "WI-FI ONLY. IT CONTINUES IN THE BACKGROUND AND RESUMES AFTER A BREAK.")
        }
    }
}

@Composable
private fun ColumnScope.ArchiveSheet(archive: LoreStore.Archive, onClose: () -> Unit) {
    SheetLabel("PACK FROM A FILE")
    FieldText(archive.name, 16.sp, Modifier.padding(top = 6.dp), color = FieldColors.OnPanel, maxLines = 2, overflow = TextOverflow.Ellipsis)
    SheetNote(archive.detail)
    DeleteKey("TAKE OFF THE SHELF", "TAP AGAIN (THE FILE ITSELF STAYS)") { LoreStore.removeArchive(archive.key); onClose() }
}

// ------------------------------------------------------------------- reader

/** A whole article or note, for checking an answer against its source. */
@Composable
private fun ReaderScreen(reading: LoreStore.Reading) {
    BackHandler { LoreStore.closeReading() }
    FieldScreen {
        StatusHeader("LORE", LedMode.Off, "READING", left = reading.title.uppercase(), right = "")
        FieldKey("BACK", { LoreStore.closeReading() }, Modifier.fillMaxWidth(), height = 40.dp, size = 11.sp)
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var heading = ""
                for (passage in reading.passages) {
                    if (passage.heading != heading && passage.heading.isNotEmpty()) {
                        heading = passage.heading
                        FieldProse(AnnotatedString(heading), Modifier.padding(top = 8.dp), size = 15.sp, weight = FontWeight.Bold)
                    }
                    FieldProse(AnnotatedString(passage.text))
                }
            }
        }
    }
}
