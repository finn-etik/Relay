package dev.field.list

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.field.ui.EmptyCard
import dev.field.ui.FieldCheckRow
import dev.field.ui.FieldColors
import dev.field.ui.FieldFold
import dev.field.ui.FieldInput
import dev.field.ui.FieldKey
import dev.field.ui.FieldRow
import dev.field.ui.FieldScreen
import dev.field.ui.FieldText
import dev.field.ui.Fmt
import dev.field.ui.KeyVariant
import dev.field.ui.Kit
import dev.field.ui.LedMode
import dev.field.ui.LocalAccent
import dev.field.ui.PaperCard
import dev.field.ui.RegisterPanel
import dev.field.ui.SectionHeader
import dev.field.ui.SkinLine
import dev.field.ui.StatusHeader
import dev.field.ui.fieldContent
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * LIST: to-do lists in the Field Design Language. Skin: signal magenta.
 *
 * Lists are Markdown files grouped into spaces (folders). A space whose
 * folder is kept in sync between two phones is a shared space. The screen
 * shows ListStore's state and sends it commands; see ListStore and TaskFile.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ListStore.load(applicationContext)
        ListSync.load(applicationContext)
        if (savedInstanceState == null) receive(intent) // not again after a rotation
        fieldContent(FieldColors.SignalMagenta, FieldColors.SignalMagentaOnDark) { ListApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** Coming back to the app is when the partner's changes are most likely to be waiting. */
    override fun onResume() {
        super.onResume()
        ListStore.refresh(applicationContext)
        ListSync.now(applicationContext)
        ticker.postDelayed(tick, SYNC_EVERY_MS)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    // While LIST is on screen, shared spaces are looked at once a minute, so the other person's ticks arrive.
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            ListSync.now(applicationContext)
            ticker.postDelayed(this, SYNC_EVERY_MS)
        }
    }

    /** Shared text becomes a task: the subject (or first line) is the title, further lines its steps. */
    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val all = listOf(subject, text).filter { it.isNotEmpty() }.joinToString("\n")
        if (all.isNotEmpty()) ListStore.addShared(applicationContext, all)
    }
}

/** A task together with the list it lives in. */
private data class Entry(val ref: ListRef, val task: Task)

private const val HOME = "home"
private const val DAY = "day"
private const val PLANNED = "planned"
private const val LIST_PREFIX = "list:"

private const val SYNC_EVERY_MS = 60_000L

/** "14:32" for a moment today, "25 NOV 14:32" otherwise. */
private fun timeOfDay(at: Long): String {
    val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    val pattern = if (time.toLocalDate() == LocalDate.now()) "HH:mm" else "d MMM HH:mm"
    return time.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH)).uppercase()
}

@Composable
fun ListApp() {
    val app = LocalContext.current.applicationContext
    val state by ListStore.state.collectAsState()

    // Which screen: HOME, DAY, PLANNED, or LIST_PREFIX + a list's key. A string, so it survives rotation.
    var view by rememberSaveable { mutableStateOf(HOME) }
    var selected by rememberSaveable { mutableStateOf("") } // id of the open task, "" = none

    val incoming by ListStore.incoming.collectAsState()
    LaunchedEffect(incoming) {
        val target = incoming
        if (target != null) {
            view = target
            selected = ""
            ListStore.incoming.value = null
        }
    }

    // Shared lists change behind the app's back: look again while it is on screen.
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            ListStore.refresh(app)
        }
    }

    BackHandler(enabled = view != HOME || selected.isNotEmpty()) {
        if (selected.isNotEmpty()) selected = "" else view = HOME
    }

    val today = LocalDate.now()
    val all = state.lists.flatMap { l -> l.list.tasks.map { Entry(l.ref, it) } }
    val myDay = all.filter { Tasks.inMyDay(it.task, state.picks, today) }
    val planned = all.filter { !it.task.done && it.task.due != null }.sortedBy { it.task.due }

    fun go(target: String) {
        view = target
        selected = ""
    }

    when {
        view == DAY -> TaskScreen(
            state, name = "MY DAY", kind = DAY, listRef = state.defaultList?.ref, showList = true,
            open = myDay.sortedWith(compareBy({ !it.task.important }, { it.task.due ?: LocalDate.MAX })), done = emptyList(),
            selected = selected, onSelect = { selected = it }, onBack = { go(HOME) },
        )
        view == PLANNED -> TaskScreen(
            state, name = "PLANNED", kind = PLANNED, listRef = null, showList = true,
            open = planned, done = emptyList(),
            selected = selected, onSelect = { selected = it }, onBack = { go(HOME) },
        )
        view.startsWith(LIST_PREFIX) -> {
            val loaded = state.find(view.removePrefix(LIST_PREFIX))
            if (loaded == null) {
                // The list is gone (deleted here or on the other phone), or nothing is read yet.
                LaunchedEffect(state.loaded) { if (state.loaded) go(HOME) }
                FieldScreen { StatusHeader("LIST", LedMode.Fast, "READING", left = "ONE MOMENT") }
            } else {
                val ordered = Tasks.ordered(loaded.list.tasks).map { Entry(loaded.ref, it) }
                TaskScreen(
                    state, name = loaded.ref.name, kind = LIST_PREFIX, listRef = loaded.ref, showList = false,
                    open = ordered.filter { !it.task.done }, done = ordered.filter { it.task.done },
                    selected = selected, onSelect = { selected = it }, onBack = { go(HOME) },
                )
            }
        }
        else -> Home(state, myDay.size, planned.size, all.count { Tasks.isOverdue(it.task, today) }, onOpen = { go(it) })
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun Home(state: ListState, myDay: Int, planned: Int, overdue: Int, onOpen: (String) -> Unit) {
    val app = LocalContext.current.applicationContext
    val accent = LocalAccent.current
    val openTotal = state.lists.sumOf { it.list.open }
    var forget by remember { mutableStateOf("") } // id of the space whose FORGET key was pressed once

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) ListStore.addSpace(app, uri)
    }

    FieldScreen {
        StatusHeader(
            title = "LIST",
            led = if (overdue > 0) LedMode.Solid else LedMode.Off,
            status = if (!state.loaded) "READING" else if (overdue > 0) "OVERDUE" else "READY",
            left = "OPEN " + Fmt.pad3(openTotal),
            right = Fmt.pad2(state.lists.size) + " LISTS",
        )

        RegisterPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FieldText("MY DAY", 16.sp, color = FieldColors.OnPanel)
                FieldText("PLANNED " + Fmt.pad3(planned), 10.sp, color = FieldColors.Dim, tracking = 1.sp)
            }
            FieldText(
                Fmt.pad2(myDay) + if (myDay == 1) " TASK" else " TASKS", 22.sp, Modifier.padding(top = 12.dp),
                weight = FontWeight.Black, color = if (overdue > 0) accent else FieldColors.OnPanel,
            )
            FieldText(
                if (overdue > 0) Fmt.pad2(overdue) + " OVERDUE" else if (myDay == 0) "NOTHING DUE TODAY" else "DUE OR PICKED FOR TODAY",
                10.sp, Modifier.padding(top = 10.dp), color = accent, tracking = 1.sp,
            )
        }

        if (state.notice.isNotEmpty()) {
            EmptyCard("NOTE", state.notice)
            FieldKey("OK", { ListStore.clearNotice(app) }, Modifier.fillMaxWidth(), height = 36.dp, size = 11.sp)
        }

        FieldRow(index = 1, selected = false, onClick = { onOpen(DAY) }, trailing = Fmt.pad2(myDay)) {
            FieldText("MY DAY", 12.sp, tracking = 1.sp)
        }
        FieldRow(index = 2, selected = false, onClick = { onOpen(PLANNED) }, trailing = Fmt.pad2(planned)) {
            FieldText("PLANNED", 12.sp, tracking = 1.sp)
        }

        // ---- one section per space ----
        state.spaces.forEach { space ->
            key(space.id) {
                var name by rememberSaveable { mutableStateOf("") }
                fun create() {
                    if (name.isNotBlank()) {
                        ListStore.createList(app, space.id, name)
                        name = ""
                    }
                }
                SectionHeader(space.name + if (space.cloud) " / DRIME" else if (space.tree != null) " / FOLDER" else "")
                val lists = state.lists.filter { it.ref.spaceId == space.id }
                if (space.id in state.unavailable) {
                    EmptyCard("FOLDER UNAVAILABLE", "THIS SPACE'S FOLDER WAS MOVED OR ITS ACCESS WITHDRAWN. THE FILES ARE UNTOUCHED. FORGET THE SPACE BELOW AND ADD THE FOLDER AGAIN.")
                } else {
                    lists.forEachIndexed { i, l ->
                        FieldRow(index = i + 1, selected = false, onClick = { onOpen(LIST_PREFIX + l.ref.key) }, trailing = l.list.open.toString() + " OPEN") {
                            FieldText(l.ref.name.uppercase(), 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FieldInput(name, { name = it }, "NEW LIST", Modifier.weight(1f), onDone = { create() })
                        FieldKey("ADD", { create() }, Modifier.weight(0.3f), size = 12.sp, variant = KeyVariant.Accent)
                    }
                }
            }
        }

        // ---- spaces: set up once, so folded away ----
        FieldFold(
            "SPACES AND SHARING",
            Fmt.pad2(state.spaces.count { it.cloud }) + " DRIME / " + Fmt.pad2(state.spaces.count { it.tree != null }) + " FOLDER",
        ) {
            DrimeSpaces(state)

            PaperCard {
                FieldText("FOLDER SPACES", 12.sp, tracking = 1.sp)
                FieldText(
                    "WITHOUT DRIME: BOTH PHONES KEEP THE SAME FOLDER IN SYNC WITH A SYNC APP (FOR EXAMPLE SYNCTHING), " +
                        "THEN EACH ADDS THAT FOLDER HERE. EVERY LIST IS A PLAIN TEXT FILE IN IT.",
                    9.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim, lineHeight = 14.sp,
                )
            }
            FieldKey("ADD A FOLDER SPACE", { folderPicker.launch(null) }, Modifier.fillMaxWidth(), height = 44.dp, size = 12.sp)
            state.spaces.filter { it.tree != null }.forEachIndexed { i, space ->
                FieldRow(
                    index = i + 1, selected = forget == space.id,
                    onClick = {
                        // Two taps: forgetting a space hides its lists (the files stay).
                        if (forget == space.id) {
                            ListStore.removeSpace(app, space.id)
                            forget = ""
                        } else {
                            forget = space.id
                        }
                    },
                    trailing = if (forget == space.id) "TAP TO CONFIRM" else "FORGET",
                ) {
                    FieldText(space.name, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL MAGENTA / EXT: INPUT ROW, CHECK ROW")
    }
}

/**
 * Shared spaces through Drime: connect an account, turn a Drime folder into
 * a space (a new one, one of yours, or one shared with you), and share a
 * space with another Drime account.
 */
@Composable
private fun ColumnScope.DrimeSpaces(state: ListState) {
    val app = LocalContext.current.applicationContext
    val accent = LocalAccent.current
    val scope = rememberCoroutineScope()
    val sync by ListSync.status.collectAsState()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var folders by remember { mutableStateOf<ListSync.Folders?>(null) } // non-null = the folder chooser is open
    var newName by remember { mutableStateOf("") }
    var forget by remember { mutableStateOf("") }

    /** Runs a Drime call off the main thread; [work] returns what went wrong, or null. */
    fun drime(work: () -> String?) {
        busy = true
        note = ""
        scope.launch {
            note = withContext(Dispatchers.IO) { runCatching(work).getOrElse { (it.message ?: "FAILED").uppercase() } }.orEmpty()
            busy = false
        }
    }

    PaperCard {
        FieldText(if (sync.connected) "DRIME / " + sync.account.uppercase() else "SHARE THROUGH DRIME", 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FieldText(
            when {
                !sync.connected -> "A DRIME SPACE IS A FOLDER IN YOUR DRIME CLOUD. SHARE IT WITH ANOTHER DRIME ACCOUNT AND YOU BOTH SEE AND TICK THE SAME LISTS. " +
                    "IN DRIME: ACCOUNT SETTINGS, DEVELOPERS, CREATE A TOKEN, AND PASTE IT HERE."
                sync.running -> "SYNCING"
                sync.lastAt > 0 -> "SYNCED " + timeOfDay(sync.lastAt) + ". CHANGES ARE SENT AS YOU MAKE THEM AND FETCHED EVERY MINUTE WHILE LIST IS OPEN."
                else -> "NOT SYNCED YET"
            },
            9.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim, lineHeight = 14.sp,
        )
        if (sync.error.isNotEmpty()) FieldText(sync.error, 9.sp, Modifier.padding(top = 4.dp), color = accent, lineHeight = 14.sp)
        if (note.isNotEmpty()) FieldText(note, 9.sp, Modifier.padding(top = 4.dp), color = accent, lineHeight = 14.sp)
    }

    if (!sync.connected) {
        FieldInput(token, { token = it }, "DRIME ACCESS TOKEN")
        FieldKey(
            if (busy) "CONNECTING" else "CONNECT",
            { drime { ListSync.connect(app, token).also { if (it == null) token = "" } } },
            Modifier.fillMaxWidth(), height = 40.dp, size = 12.sp, variant = KeyVariant.Accent, enabled = token.isNotBlank() && !busy,
        )
        return
    }

    // ---- the spaces, each with a way to share it ----
    state.spaces.filter { it.cloud }.forEachIndexed { i, space ->
        key(space.id) {
            var email by rememberSaveable { mutableStateOf("") }
            FieldRow(
                index = i + 1, selected = forget == space.id,
                onClick = {
                    // Two taps: forgetting a space removes its lists from this phone (they stay in Drime).
                    if (forget == space.id) {
                        ListStore.removeSpace(app, space.id)
                        forget = ""
                    } else {
                        forget = space.id
                    }
                },
                trailing = if (forget == space.id) "TAP TO CONFIRM" else "FORGET",
            ) {
                FieldText(space.name, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FieldInput(email, { email = it }, "SHARE WITH (DRIME E-MAIL)", Modifier.weight(1f), height = 44.dp)
                FieldKey(
                    "SHARE",
                    {
                        val to = email
                        drime { ListSync.share(app, space, to) ?: "SHARED ${space.name} WITH ${to.trim().uppercase()}. THEY ADD IT IN THEIR LIST UNDER \"SHARED WITH YOU\"." }
                        email = ""
                    },
                    Modifier.weight(0.4f), height = 44.dp, size = 11.sp, enabled = email.contains('@') && !busy,
                )
            }
        }
    }

    // ---- adding a space: choose a Drime folder ----
    val chooser = folders
    if (chooser == null) {
        FieldKey(
            if (busy) "ASKING DRIME" else "ADD A DRIME SPACE",
            {
                drime {
                    folders = ListSync.folders(app)
                    null
                }
            },
            Modifier.fillMaxWidth(), height = 44.dp, size = 12.sp, variant = KeyVariant.Accent, enabled = !busy,
        )
    } else {
        val taken = state.spaces.filter { it.cloud }.map { it.folderId }.toSet()
        fun add(name: String, id: Long, hash: String) {
            ListStore.addCloudSpace(app, name, id, hash)
            folders = null
        }
        SectionHeader("NEW SPACE")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldInput(newName, { newName = it }, "NAME, E.G. HOME", Modifier.weight(1f), height = 44.dp)
            FieldKey(
                "CREATE",
                {
                    val name = newName.trim()
                    drime {
                        val folder = ListSync.createFolder(app, name)
                        add(name, folder.id, folder.hash)
                        newName = ""
                        null
                    }
                },
                Modifier.weight(0.4f), height = 44.dp, size = 11.sp, variant = KeyVariant.Accent, enabled = newName.isNotBlank() && !busy,
            )
        }
        val shared = chooser.shared.filter { it.id !in taken }
        if (shared.isNotEmpty()) {
            SectionHeader("SHARED WITH YOU")
            shared.forEachIndexed { i, f ->
                FieldRow(index = i + 1, selected = false, onClick = { add(f.name, f.id, f.hash) }, trailing = "ADD") {
                    FieldText(f.name.uppercase(), 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val mine = chooser.mine.filter { it.id !in taken }
        if (mine.isNotEmpty()) {
            SectionHeader("YOUR DRIME FOLDERS")
            mine.take(30).forEachIndexed { i, f ->
                FieldRow(index = i + 1, selected = false, onClick = { add(f.name, f.id, f.hash) }, trailing = "ADD") {
                    FieldText(f.name.uppercase(), 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        FieldKey("CANCEL", { folders = null }, Modifier.fillMaxWidth(), height = 40.dp, size = 11.sp)
    }

    var sure by remember { mutableStateOf(false) }
    FieldKey(
        if (sure) "TAP AGAIN TO DISCONNECT (LISTS STAY, SYNC STOPS)" else "DISCONNECT DRIME",
        {
            if (sure) {
                sure = false
                ListSync.disconnect(app)
            } else {
                sure = true
            }
        },
        Modifier.fillMaxWidth(), height = 40.dp, size = 10.sp,
    )
}

// ---------------------------------------------------------------- a list, MY DAY, PLANNED

@Composable
private fun TaskScreen(
    state: ListState,
    name: String,
    kind: String,
    /** Where quick add puts a task: the list itself, or the default list for MY DAY; null = no quick add. */
    listRef: ListRef?,
    showList: Boolean,
    open: List<Entry>,
    done: List<Entry>,
    selected: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext
    val today = LocalDate.now()
    var quick by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val overdue = open.count { Tasks.isOverdue(it.task, today) }
    val isList = kind == LIST_PREFIX

    fun add() {
        val ref = listRef ?: return
        val task = Tasks.quick(quick, today)
        if (task.title.isBlank()) return
        ListStore.addTask(app, ref, task, pickForToday = kind == DAY)
        quick = ""
    }

    // The open task and its controls live in the sheet, so opening one does not push the list around.
    val openEntry = (open + done).firstOrNull { it.task.id == selected }
    val sheet: (@Composable ColumnScope.() -> Unit)? = if (openEntry == null) null else {
        { TaskDetail(openEntry, state.picks, today, onClose = { onSelect("") }) }
    }

    @Composable
    fun rows(entries: List<Entry>) {
        entries.forEach { e ->
            key(e.task.id) {
                TaskRow(e, showList, state.picks, today, selected == e.task.id) { onSelect(if (selected == e.task.id) "" else e.task.id) }
            }
        }
    }

    FieldScreen(sheet = sheet, onDismissSheet = { onSelect("") }) {
        StatusHeader(
            title = "LIST",
            led = if (overdue > 0) LedMode.Solid else LedMode.Off,
            status = if (overdue > 0) "OVERDUE" else if (isList) "LIST" else "VIEW",
            left = name.uppercase(),
            right = Fmt.pad2(open.size) + " OPEN",
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey("< LISTS", onBack, Modifier.weight(1f), height = 40.dp, size = 12.sp)
            FieldKey("SYNC", { ListStore.refresh(app); ListSync.now(app) }, Modifier.weight(1f), height = 40.dp, size = 12.sp)
        }

        if (state.notice.isNotEmpty()) {
            EmptyCard("NOTE", state.notice)
            FieldKey("OK", { ListStore.clearNotice(app) }, Modifier.fillMaxWidth(), height = 36.dp, size = 11.sp)
        }

        if (listRef != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FieldInput(quick, { quick = it }, "ADD A TASK", Modifier.weight(1f), onDone = { add() })
                FieldKey("ADD", { add() }, Modifier.weight(0.3f), size = 12.sp, variant = KeyVariant.Accent)
            }
            FieldText(
                (if (kind == DAY) "GOES TO \"" + listRef.name.uppercase() + "\". " else "") +
                    "END WITH TODAY, TOMORROW OR A WEEKDAY (FRI) FOR A DUE DATE, AND ! FOR IMPORTANT.",
                9.sp, color = FieldColors.Dim, lineHeight = 14.sp,
            )
        } else if (kind == DAY) {
            EmptyCard("NO LISTS YET", "MAKE A LIST FIRST; MY DAY COLLECTS TASKS FROM ALL OF THEM.")
        }

        if (open.isEmpty() && done.isEmpty()) {
            EmptyCard(
                "NOTHING HERE",
                when (kind) {
                    DAY -> "TASKS DUE TODAY APPEAR HERE BY THEMSELVES. OPEN ANY TASK AND PRESS MY DAY TO ADD IT FOR TODAY."
                    PLANNED -> "TASKS WITH A DUE DATE APPEAR HERE, SOONEST FIRST."
                    else -> "TYPE A TASK ABOVE AND PRESS ADD."
                },
            )
        }

        rows(open)
        if (done.isNotEmpty()) {
            SectionHeader("DONE " + Fmt.pad3(done.size))
            rows(done)
        }

        // Housekeeping for the list itself: needed now and then, so folded away.
        if (isList && listRef != null) {
            FieldFold("THIS LIST", "CLEAR DONE TASKS / DELETE THE LIST") {
                FieldKey(
                    "CLEAR " + Fmt.pad2(done.size) + " DONE", { ListStore.edit(app, listRef) { it.clearDone() } },
                    Modifier.fillMaxWidth(), height = 40.dp, size = 12.sp, enabled = done.isNotEmpty(),
                )
                // Destructive: plain, and it asks twice.
                FieldKey(
                    if (confirmDelete) "TAP AGAIN TO DELETE THE LIST AND ITS FILE" else "DELETE LIST",
                    {
                        if (confirmDelete) {
                            ListStore.deleteList(app, listRef)
                            onBack()
                        } else {
                            confirmDelete = true
                        }
                    },
                    Modifier.fillMaxWidth(), height = 36.dp, size = 11.sp,
                )
            }
        }

        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL MAGENTA / EXT: INPUT ROW, CHECK ROW")
    }
}

@Composable
private fun TaskRow(e: Entry, showList: Boolean, picks: Map<String, Long>, today: LocalDate, selected: Boolean, onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val accent = LocalAccent.current
    val t = e.task
    val meta = listOfNotNull(
        if (Tasks.isOverdue(t, today)) "OVERDUE" else null,
        if (showList) e.ref.name.uppercase() else null,
        if (t.steps.isNotEmpty()) "${t.stepsDone}/${t.steps.size} STEPS" else null,
        t.remindAt?.takeIf { !t.done }?.let { "REMIND " + Fmt.pad2(it.hour) + ":" + Fmt.pad2(it.minute) },
        if (!t.done && picks[t.id] == today.toEpochDay()) "MY DAY" else null,
    ).joinToString(" / ")
    FieldCheckRow(
        checked = t.done,
        onCheck = { ListStore.edit(app, e.ref) { it.toggle(t.id) } },
        selected = selected,
        onClick = onClick,
        trailing = t.due?.takeIf { !t.done }?.let { Tasks.dueLabel(it, today) }.orEmpty(),
    ) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.important && !t.done) FieldText("!", 12.sp, color = accent)
                FieldText(
                    t.title, 12.sp, weight = if (t.done) FontWeight.Normal else FontWeight.Bold,
                    color = if (t.done) FieldColors.Dim else FieldColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (meta.isNotEmpty()) {
                FieldText(
                    meta, 9.sp, Modifier.padding(top = 2.dp),
                    color = if (Tasks.isOverdue(t, today)) accent else FieldColors.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The open task: everything that can be done to it. Drawn in the screen's sheet. */
@Composable
private fun ColumnScope.TaskDetail(e: Entry, picks: Map<String, Long>, today: LocalDate, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val accent = LocalAccent.current
    val t = e.task
    val id = t.id
    var text by rememberSaveable(id) { mutableStateOf("") }
    var confirmDelete by remember(id) { mutableStateOf(false) }
    var flash by remember(id) { mutableStateOf("") }

    fun edit(change: (TaskList) -> TaskList) = ListStore.edit(app, e.ref, change)

    // Reminders are notifications: ask for the permission the first time one is set (Android 13+).
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun remind(at: LocalDateTime?) {
        edit { it.setReminder(id, at) }
        val ask = at != null && Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    FieldText(t.title, 16.sp, color = FieldColors.OnPanel, maxLines = 4, overflow = TextOverflow.Ellipsis)
    FieldText(
        listOfNotNull(
            e.ref.name.uppercase(),
            t.due?.let { "DUE " + Tasks.dueLabel(it, today) },
            t.remindAt?.let { "REMIND " + Tasks.dueLabel(it.toLocalDate(), today) + " " + Fmt.pad2(it.hour) + ":" + Fmt.pad2(it.minute) },
        ).joinToString(" / "),
        10.sp, Modifier.padding(top = 6.dp), color = FieldColors.Dim, tracking = 1.sp,
    )
    if (flash.isNotEmpty()) FieldText(flash, 10.sp, Modifier.padding(top = 6.dp), color = accent, tracking = 1.sp)

    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldKey(if (t.done) "UNDO" else "DONE", { edit { it.toggle(id) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp, variant = KeyVariant.Accent)
        FieldKey("MY DAY", { ListStore.togglePick(app, id) }, Modifier.weight(1f), height = 40.dp, size = 12.sp, active = picks[id] == today.toEpochDay())
        FieldKey("!", { edit { it.toggleImportant(id) } }, Modifier.weight(0.5f), height = 40.dp, size = 12.sp, active = t.important)
    }

    FieldText("DUE", 10.sp, Modifier.padding(top = 12.dp), color = FieldColors.Dim, tracking = 2.sp)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val from = t.due ?: today
        FieldKey("TODAY", { edit { it.setDue(id, today) } }, Modifier.weight(1.2f), height = 36.dp, size = 10.sp, active = t.due == today)
        FieldKey("TMRW", { edit { it.setDue(id, today.plusDays(1)) } }, Modifier.weight(1.1f), height = 36.dp, size = 10.sp, active = t.due == today.plusDays(1))
        FieldKey("+1 D", { edit { it.setDue(id, from.plusDays(1)) } }, Modifier.weight(1f), height = 36.dp, size = 10.sp)
        FieldKey("+1 W", { edit { it.setDue(id, from.plusWeeks(1)) } }, Modifier.weight(1f), height = 36.dp, size = 10.sp)
        FieldKey("NONE", { edit { it.setDue(id, null) } }, Modifier.weight(1.1f), height = 36.dp, size = 10.sp, enabled = t.due != null)
    }

    FieldText("REMIND", 10.sp, Modifier.padding(top = 12.dp), color = FieldColors.Dim, tracking = 2.sp)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldKey("IN 1 H", { remind(LocalDateTime.now().plusHours(1).withSecond(0).withNano(0)) }, Modifier.weight(1.2f), height = 36.dp, size = 10.sp)
        FieldKey(
            "18:00",
            {
                val evening = LocalDateTime.of(LocalDate.now(), LocalTime.of(18, 0))
                remind(if (evening.isAfter(LocalDateTime.now())) evening else evening.plusDays(1))
            },
            Modifier.weight(1f), height = 36.dp, size = 10.sp,
        )
        FieldKey("TMRW 09", { remind(LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(9, 0))) }, Modifier.weight(1.3f), height = 36.dp, size = 10.sp)
        FieldKey("NONE", { remind(null) }, Modifier.weight(1f), height = 36.dp, size = 10.sp, enabled = t.remindAt != null)
    }

    FieldText("STEPS " + t.stepsDone + "/" + t.steps.size, 10.sp, Modifier.padding(top = 12.dp), color = FieldColors.Dim, tracking = 2.sp)
    t.steps.forEachIndexed { i, step ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { edit { it.toggleStep(id, i) } },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(18.dp).border(1.5.dp, FieldColors.OnPanel), contentAlignment = Alignment.Center) {
                if (step.done) Box(Modifier.size(10.dp).background(accent))
            }
            FieldText(
                step.text, 11.sp, Modifier.weight(1f), weight = FontWeight.Normal,
                color = if (step.done) FieldColors.Dim else FieldColors.OnPanel, lineHeight = 15.sp,
            )
            FieldKey("X", { edit { it.removeStep(id, i) } }, Modifier.width(32.dp), height = 28.dp, size = 10.sp)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FieldInput(text, { text = it }, "STEP, OR NEW TITLE", Modifier.weight(1f), height = 44.dp, onDone = {
            edit { it.addStep(id, text) }
            text = ""
        })
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldKey("ADD STEP", {
            edit { it.addStep(id, text) }
            text = ""
        }, Modifier.weight(1f), height = 36.dp, size = 10.sp, enabled = text.isNotBlank())
        FieldKey("RENAME", {
            val title = text
            edit { it.rename(id, title) }
            text = ""
        }, Modifier.weight(1f), height = 36.dp, size = 10.sp, enabled = text.isNotBlank())
    }

    // ---- kit links ----
    FieldText("KIT", 10.sp, Modifier.padding(top = 12.dp), color = FieldColors.Dim, tracking = 2.sp)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 0 minutes = whatever length GATE is set to.
        FieldKey("FOCUS IN GATE", {
            flash = if (Kit.startFocus(context, 0)) "GATE SESSION STARTED" else "GATE IS NOT INSTALLED"
        }, Modifier.weight(1f), height = 36.dp, size = 10.sp)
        FieldKey("NOTE IN PAGE", {
            val body = if (t.steps.isEmpty()) t.title else t.steps.joinToString("\n") { "- [" + (if (it.done) "x" else " ") + "] " + it.text }
            if (!Kit.sendText(context, Kit.PAGE, t.title, body)) flash = "PAGE IS NOT INSTALLED"
        }, Modifier.weight(1f), height = 36.dp, size = 10.sp)
    }

    FieldKey("CLOSE", onClose, Modifier.fillMaxWidth().padding(top = 12.dp), height = 40.dp, size = 12.sp)
    // Destructive: last, plain, and it asks twice.
    FieldKey(
        if (confirmDelete) "TAP AGAIN TO DELETE THIS TASK" else "DELETE",
        {
            if (confirmDelete) {
                edit { it.remove(id) }
                onClose()
            } else {
                confirmDelete = true
            }
        },
        Modifier.fillMaxWidth().padding(top = 8.dp), height = 36.dp, size = 11.sp,
    )
}
