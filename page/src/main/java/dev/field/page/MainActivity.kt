package dev.field.page

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.field.ui.FieldKey
import dev.field.ui.FieldRow
import dev.field.ui.FieldScreen
import dev.field.ui.FieldText
import dev.field.ui.FieldTheme
import dev.field.ui.Fmt
import dev.field.ui.KeyVariant
import dev.field.ui.LedMode
import dev.field.ui.LocalAccent
import dev.field.ui.RegisterPanel
import dev.field.ui.SectionHeader
import dev.field.ui.SkinLine
import dev.field.ui.StatusHeader
import dev.field.ui.fieldEdgeToEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Signal violet, lifted for contrast on the dark body. */
private val VioletOnDark = Color(0xFFA98BFF)

/** The local calendar day that word counts are booked to. */
fun today(): Long = LocalDate.now().toEpochDay()

/** PAGE: focused writing in the Field Design Language. Skin: signal violet. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PageStore.load(applicationContext)
        // Set the palette before the first frame, so a dark start never flashes light.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyPalette(isDark(PageStore.state.value.settings.theme, night))
        setContent { PageApp(this) }
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
    FieldTheme(if (dark) VioletOnDark else FieldColors.SignalViolet) {
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

    var docs by remember { mutableStateOf<List<DocInfo>?>(null) }
    LaunchedEffect(Unit) { docs = withContext(Dispatchers.IO) { PageStore.list(context) } }
    var importFailed by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = PageStore.readUri(context, uri)
            if (text == null) importFailed = true else onOpen(PageStore.create(context, text))
        }
    }

    fun set(edit: (Settings) -> Settings) = PageStore.update(context) { it.copy(settings = edit(it.settings)) }

    val written = goals.today(day)
    val met = written >= goals.target
    val streak = goals.streak(day)
    val count = docs?.size ?: 0

    FieldScreen {
        StatusHeader(
            title = "PAGE",
            led = if (met) LedMode.Solid else LedMode.Off,
            status = if (met) "GOAL MET" else "READY",
            left = "DOCS " + Fmt.pad3(count),
            right = "STREAK " + Fmt.pad3(streak) + " D",
        )

        RegisterPanel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FieldText("TODAY", 16.sp, color = FieldColors.Paper)
                FieldText("GOAL ${goals.target} W", 10.sp, color = FieldColors.Dim, tracking = 1.sp)
            }
            FieldText(
                "$written W",
                22.sp, Modifier.padding(top = 12.dp),
                weight = FontWeight.Black,
                color = if (met) accent else FieldColors.Paper,
            )
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).background(FieldColors.Dim)) {
                val fraction = (written.toFloat() / goals.target).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(accent))
            }
            FieldText(
                if (met) "DONE FOR TODAY / KEEP GOING IF IT FLOWS" else "${goals.target - written} W TO GO",
                10.sp, Modifier.padding(top = 10.dp), color = accent, tracking = 1.sp,
            )
        }

        SectionHeader("DOCUMENTS")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldKey("NEW", { onOpen(PageStore.create(context)) }, Modifier.weight(1f), size = 12.sp, variant = KeyVariant.Accent)
            FieldKey("IMPORT", { importFailed = false; importer.launch(arrayOf("text/*", "application/octet-stream")) }, Modifier.weight(1f), size = 12.sp)
        }
        if (importFailed) {
            EmptyCard("IMPORT FAILED", "NOT A READABLE TEXT FILE, OR LARGER THAN 2 MB.")
        }
        val list = docs
        if (list != null && list.isEmpty()) {
            EmptyCard("NO DOCUMENTS", "TAP NEW AND START WRITING. THE FIRST LINE BECOMES THE TITLE.")
        } else if (list != null) {
            list.forEachIndexed { i, doc ->
                FieldRow(index = i + 1, selected = false, onClick = { onOpen(doc.id) }, trailing = "${doc.words} W") {
                    FieldText(doc.title.ifEmpty { "UNTITLED" }, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        SectionHeader("DAILY GOAL")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Goals.TARGETS.forEach { t ->
                FieldKey(
                    "$t",
                    { PageStore.update(context) { it.copy(goals = it.goals.copy(target = t)) } },
                    Modifier.weight(1f), height = 40.dp, size = 12.sp, active = goals.target == t,
                )
            }
        }

        SectionHeader("PAPER")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                FieldKey(m.name, { set { it.copy(theme = m) } }, Modifier.weight(1f), height = 40.dp, size = 12.sp, active = settings.theme == m)
            }
        }

        SectionHeader("TYPE")
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

        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL VIOLET / NO EXTENSIONS")
    }
}
