package dev.field.page

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.field.ui.EmptyCard
import dev.field.ui.FieldColors
import dev.field.ui.FieldFold
import dev.field.ui.FieldInput
import dev.field.ui.FieldKey
import dev.field.ui.FieldRow
import dev.field.ui.FieldScreen
import dev.field.ui.FieldText
import dev.field.ui.FieldTheme
import dev.field.ui.Fmt
import dev.field.ui.KeyVariant
import dev.field.ui.LedMode
import dev.field.ui.LocalAccent
import dev.field.ui.PaperCard
import dev.field.ui.RegisterPanel
import dev.field.ui.SectionHeader
import dev.field.ui.SkinLine
import dev.field.ui.StatusHeader
import dev.field.ui.fieldEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Sage, lifted for contrast on the dark body. */
private val SageOnDark = Color(0xFF8DB3A4)

/** The local calendar day that word counts are booked to. */
fun today(): Long = LocalDate.now().toEpochDay()

/** PAGE: focused writing in the Field Design Language. Skin: sage, kept quiet on purpose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PageStore.load(applicationContext)
        PageSync.load(applicationContext)
        // Set the palette before the first frame, so a dark start never flashes light.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyPalette(isDark(PageStore.state.value.settings.theme, night))
        if (savedInstanceState == null) receive(intent) // not again after a rotation
        setContent { PageApp(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    /** Shared text becomes a new note, titled with the share's subject if it has one. */
    private fun receive(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        PageStore.flush(applicationContext) // whatever is open is saved before the new note takes its place
        PageStore.incoming.value = PageStore.create(applicationContext, Text.sharedNote(subject, text))
    }

    /** Leaving the app for any reason writes what the editor has staged. */
    override fun onStop() {
        super.onStop()
        PageStore.flush(applicationContext)
    }

    fun applyPalette(dark: Boolean) {
        FieldColors.palette = if (dark) FieldColors.Dark else FieldColors.Light
        fieldEdgeToEdge(dark)
    }
}

/** "14:32" today, "25 NOV 14:32" otherwise. */
private fun clock(at: Long): String {
    val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
    val pattern = if (time.toLocalDate() == LocalDate.now()) "HH:mm" else "d MMM HH:mm"
    return time.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.ENGLISH)).uppercase()
}

fun isDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.AUTO -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PageApp(activity: MainActivity) {
    val state by PageStore.state.collectAsState()
    val dark = isDark(state.settings.theme, isSystemInDarkTheme())
    LaunchedEffect(dark) { activity.applyPalette(dark) }

    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val incoming by PageStore.incoming.collectAsState()
    LaunchedEffect(incoming) {
        if (incoming != null) {
            open = incoming
            PageStore.incoming.value = null
        }
    }
    FieldTheme(if (dark) SageOnDark else FieldColors.Sage) {
        val id = open
        if (id == null) Library(state, onOpen = { open = it })
        else Editor(id, state, onClose = { open = null })
    }
}

@Composable
fun Library(state: PageState, onOpen: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val goals = state.goals
    val settings = state.settings
    val day = remember { today() }
    val accent = LocalAccent.current

    // null while loading; `unavailable` when the chosen folder cannot be read.
    var docs by remember { mutableStateOf<List<DocInfo>?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload, settings.folder) {
        val found = withContext(Dispatchers.IO) { PageStore.list(context) }
        unavailable = found == null
        docs = found ?: emptyList()
    }

    // Sync: once whenever the library comes up (app start, back from a note), and on request.
    val sync by PageSync.status.collectAsState()
    var token by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    fun syncNow() {
        scope.launch {
            withContext(Dispatchers.IO) { PageSync.run(context) }
            reload++
        }
    }
    LaunchedEffect(sync.connected) { if (sync.connected) syncNow() }

    fun newNote(text: String = "") {
        val id = PageStore.create(context, text)
        if (id == null) notice = "COULD NOT CREATE A FILE IN THE NOTES FOLDER." else onOpen(id)
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = PageStore.readUri(context, uri)
            if (text == null) notice = "IMPORT FAILED: NOT A READABLE TEXT FILE, OR LARGER THAN 2 MB." else newNote(text)
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { PageStore.useFolder(context, uri) }
                notice = if (ok) "" else "COULD NOT USE THAT FOLDER. PICK ANOTHER ONE."
                reload++
            }
        }
    }

    fun set(edit: (Settings) -> Settings) = PageStore.update(context) { it.copy(settings = edit(it.settings)) }

    val hasGoal = goals.target > 0
    val written = goals.today(day)
    val met = hasGoal && written >= goals.target
    val streak = goals.streak(day)
    val count = docs?.size ?: 0
    val folder = remember(settings.folder) { PageStore.folderLabel() }

    FieldScreen {
        StatusHeader(
            title = "PAGE",
            led = if (met) LedMode.Solid else LedMode.Off,
            status = if (met) "GOAL MET" else "READY",
            left = "NOTES " + Fmt.pad3(count),
            right = if (hasGoal) "STREAK " + Fmt.pad3(streak) + " D" else "",
        )

        RegisterPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FieldText("TODAY", 16.sp, color = FieldColors.OnPanel)
                FieldText(if (hasGoal) "GOAL ${goals.target} W" else "NO GOAL", 10.sp, color = FieldColors.Dim, tracking = 1.sp)
            }
            FieldText(
                "$written W",
                22.sp, Modifier.padding(top = 12.dp),
                weight = FontWeight.Black,
                color = if (met) accent else FieldColors.OnPanel,
            )
            if (hasGoal) {
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).background(FieldColors.Dim)) {
                    val fraction = (written.toFloat() / goals.target).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent))
                }
                FieldText(
                    if (met) "DONE FOR TODAY / KEEP GOING IF IT FLOWS" else "${goals.target - written} W TO GO",
                    10.sp, Modifier.padding(top = 10.dp), color = accent, tracking = 1.sp,
                )
            }
        }

        SectionHeader("NOTES")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey("NEW", { notice = ""; newNote() }, Modifier.weight(1f), size = 12.sp, variant = KeyVariant.Accent)
            FieldKey("IMPORT", { notice = ""; importer.launch(arrayOf("text/*", "application/octet-stream")) }, Modifier.weight(1f), size = 12.sp)
        }
        if (notice.isNotEmpty()) EmptyCard("NOTE", notice)
        if (sync.connected) {
            FieldText(
                when {
                    sync.running -> "SYNCING WITH DRIME"
                    sync.error.isNotEmpty() -> "SYNC FAILED / SEE SYNC BELOW"
                    sync.lastAt > 0 -> "SYNCED " + clock(sync.lastAt) + " / " + sync.summary
                    else -> "NOT SYNCED YET"
                },
                9.sp, color = if (sync.error.isNotEmpty() && !sync.running) accent else FieldColors.Dim, tracking = 1.sp,
            )
        }
        val list = docs
        if (unavailable) {
            EmptyCard("FOLDER UNAVAILABLE", "THE NOTES FOLDER WAS MOVED OR ITS ACCESS WITHDRAWN. YOUR FILES ARE UNTOUCHED. CHOOSE THE FOLDER AGAIN BELOW.")
        } else if (list != null && list.isEmpty()) {
            EmptyCard("NO NOTES", "TAP NEW AND START WRITING. THE FIRST LINE BECOMES THE TITLE AND THE FILE NAME.")
        } else if (list != null) {
            list.forEachIndexed { i, doc ->
                FieldRow(index = i + 1, selected = false, onClick = { onOpen(doc.id) }, trailing = "${doc.words} W") {
                    FieldText(doc.title.ifEmpty { doc.name }, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // ---- set once, changed rarely: folded away, with the current choice in the caption ----
        if (folder == null) {
            // Until a folder is chosen this is the one setting worth showing openly.
            FieldKey(
                "CHOOSE A FOLDER FOR YOUR NOTES", { notice = ""; folderPicker.launch(null) },
                Modifier.fillMaxWidth(), height = 44.dp, size = 12.sp, variant = KeyVariant.Accent,
            )
        }
        FieldFold("FILES", (folder?.uppercase() ?: "APP STORAGE") + " / NEW NOTES AS " + settings.ext.suffix.uppercase()) {
            PaperCard {
                FieldText(folder?.uppercase() ?: "APP STORAGE", 12.sp, tracking = 1.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FieldText(
                    if (folder != null) "EVERY NOTE IS A PLAIN FILE IN THIS FOLDER. OTHER APPS CAN OPEN, SYNC AND MOVE THEM."
                    else "NOTES ARE PRIVATE TO PAGE FOR NOW. CHOOSE A FOLDER AND THEY MOVE THERE AS PLAIN FILES OTHER APPS CAN OPEN.",
                    9.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim, lineHeight = 14.sp,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldKey(
                    if (folder == null) "CHOOSE FOLDER" else "CHANGE FOLDER",
                    { notice = ""; folderPicker.launch(null) },
                    Modifier.weight(2f), height = 40.dp, size = 12.sp,
                    variant = if (folder == null) KeyVariant.Accent else KeyVariant.Paper,
                )
                Ext.entries.forEach { e ->
                    FieldKey(e.suffix.uppercase(), { set { it.copy(ext = e) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp, active = settings.ext == e)
                }
            }
        }
        FieldFold("SYNC", if (sync.connected) "DRIME / " + sync.account.uppercase() else "OFF") {
            if (!sync.connected) {
                PaperCard {
                    FieldText("DRIME CLOUD", 12.sp, tracking = 1.sp)
                    FieldText(
                        "KEEPS YOUR NOTES IN STEP WITH A FOLDER NAMED " + PageSync.FOLDER + " IN YOUR DRIME, AS THE SAME PLAIN FILES. " +
                            "IN DRIME, OPEN ACCOUNT SETTINGS, DEVELOPERS, CREATE A TOKEN, AND PASTE IT HERE.",
                        9.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim, lineHeight = 14.sp,
                    )
                }
                FieldInput(token, { token = it }, "DRIME ACCESS TOKEN")
                FieldKey(
                    if (connecting) "CONNECTING" else "CONNECT",
                    {
                        connecting = true
                        notice = ""
                        scope.launch {
                            val problem = withContext(Dispatchers.IO) { PageSync.connect(context, token) }
                            connecting = false
                            if (problem == null) token = "" else notice = problem
                        }
                    },
                    Modifier.fillMaxWidth(), height = 40.dp, size = 12.sp, variant = KeyVariant.Accent, enabled = token.isNotBlank() && !connecting,
                )
            } else {
                PaperCard {
                    FieldText("DRIME / " + PageSync.FOLDER, 12.sp, tracking = 1.sp)
                    FieldText(
                        when {
                            sync.running -> "SYNCING"
                            sync.lastAt > 0 -> "LAST SYNC " + clock(sync.lastAt) + " / " + sync.summary
                            else -> "NOT SYNCED YET"
                        },
                        9.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim, tracking = 1.sp,
                    )
                    if (sync.error.isNotEmpty()) {
                        FieldText(sync.error, 9.sp, Modifier.padding(top = 4.dp), color = accent, lineHeight = 14.sp)
                    }
                    FieldText(
                        "SYNCS WHEN YOU OPEN PAGE AND WHEN YOU LEAVE A NOTE. A NOTE CHANGED IN TWO PLACES IS KEPT TWICE. DELETED NOTES GO TO DRIME'S TRASH.",
                        9.sp, Modifier.padding(top = 4.dp), color = FieldColors.Dim, lineHeight = 14.sp,
                    )
                }
                FieldKey("SYNC NOW", { syncNow() }, Modifier.fillMaxWidth(), height = 40.dp, size = 12.sp, enabled = !sync.running)
                var sure by remember { mutableStateOf(false) }
                FieldKey(
                    if (sure) "TAP AGAIN TO DISCONNECT (NOTES STAY ON BOTH SIDES)" else "DISCONNECT",
                    { if (sure) { sure = false; PageSync.disconnect(context) } else { sure = true } },
                    Modifier.fillMaxWidth(), height = 40.dp, size = 10.sp,
                )
            }
        }
        FieldFold("DAILY GOAL", if (goals.target > 0) goals.target.toString() + " WORDS" else "OFF") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Goals.TARGETS.forEach { t ->
                    FieldKey(
                        if (t == 0) "OFF" else "$t",
                        { PageStore.update(context) { it.copy(goals = it.goals.copy(target = t)) } },
                        Modifier.weight(1f), height = 40.dp, size = 12.sp, active = goals.target == t,
                    )
                }
            }
        }
        FieldFold("APPEARANCE", settings.theme.name + " / " + settings.face.name + " / " + listOf("SMALL", "MEDIUM", "LARGE")[settings.size.coerceIn(0, 2)]) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m ->
                    FieldKey(m.name, { set { it.copy(theme = m) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp, active = settings.theme == m)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Face.entries.forEach { f ->
                    FieldKey(f.name, { set { it.copy(face = f) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp, active = settings.face == f)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SMALL", "MEDIUM", "LARGE").forEachIndexed { i, label ->
                    FieldKey(label, { set { it.copy(size = i) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp, active = settings.size == i)
                }
            }
        }

        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SAGE / NO EXTENSIONS")
    }
}
