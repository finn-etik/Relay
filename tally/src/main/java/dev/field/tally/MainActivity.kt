package dev.field.tally

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.json.JSONArray
import java.util.Locale

/** TALLY: the AOSP calculator rebuilt in the Field Design Language. Skin: signal amber. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fieldEdgeToEdge()
        setContent { FieldTheme(FieldColors.SignalAmber) { TallyApp() } }
    }
}

private const val MAX_EXPR = 64
private const val MAX_LOG = 30
private val OPS = listOf("+", "−", "×", "÷", "^")

private val MAIN_ROWS = listOf(
    listOf("CLR", "DEL", "(", ")"),
    listOf("7", "8", "9", "÷"),
    listOf("4", "5", "6", "×"),
    listOf("1", "2", "3", "−"),
    listOf("0", ".", "%", "+"),
)

/** Scientific keys: text inserted -> key label. */
private val SCI_KEYS = listOf(
    "sin(" to "SIN", "cos(" to "COS", "tan(" to "TAN", "ln(" to "LN", "log(" to "LOG",
    "√(" to "√", "^" to "^", "π" to "π", "e" to "e", "!" to "!",
)

/** The log survives restarts: a small JSON list in SharedPreferences. */
private object LogStore {
    private const val KEY = "log"

    fun load(context: Context): List<Pair<String, String>> = try {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONArray(it).let { e -> e.getString(0) to e.getString(1) } }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(context: Context, log: List<Pair<String, String>>) {
        val arr = JSONArray()
        log.forEach { (e, r) -> arr.put(JSONArray().put(e).put(r)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("tally", Context.MODE_PRIVATE)
}

@Composable
fun TallyApp() {
    val context = LocalContext.current
    // Saveable: survives rotation and process death.
    var expr by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var sci by rememberSaveable { mutableStateOf(false) }
    var rad by rememberSaveable { mutableStateOf(false) }
    var keys by rememberSaveable { mutableIntStateOf(0) }
    val log = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(LogStore.load(context)) } }

    var uptime by remember { mutableStateOf("00:00:00") }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            uptime = Fmt.clock(System.currentTimeMillis() - start)
            delay(1000)
        }
    }

    fun input(text: String) {
        val continues = text in OPS || text == "%" || text == "!"
        val fresh = error != null || (locked && !continues)
        error = null
        locked = false
        keys++
        if (fresh) expr = text
        else if (expr.length + text.length <= MAX_EXPR) expr += text
    }

    fun del() {
        error = null
        locked = false
        keys++
        val cut = listOf("sin(", "cos(", "tan(", "ln(", "log(", "√(").firstOrNull { expr.endsWith(it) }
        expr = expr.dropLast(cut?.length ?: 1)
    }

    fun clear() {
        error = null
        locked = false
        keys++
        expr = ""
    }

    fun run() {
        if (error != null || expr.isEmpty()) return
        keys++
        val open = expr.count { it == '(' } - expr.count { it == ')' }
        val src = expr + ")".repeat(maxOf(0, open)) // auto-close parentheses
        try {
            val r = Calc.fmt(Calc.evaluate(src, rad))
            log.add(0, src to r)
            while (log.size > MAX_LOG) log.removeAt(log.lastIndex)
            LogStore.save(context, log)
            expr = r
            locked = true
        } catch (e: Calc.Err) {
            error = e.msg
            locked = false
        } catch (_: Exception) {
            error = "SYNTAX"
            locked = false
        }
    }

    fun recall(result: String) {
        error = null
        locked = true
        keys++
        expr = result
    }

    // Live preview of the expression on the line (silent while incomplete).
    val preview = remember(expr, rad, error) {
        if (error != null || expr.isEmpty()) null
        else try { Calc.fmt(Calc.evaluate(expr, rad)) } catch (_: Exception) { null }
    }

    val led = when {
        error != null -> LedMode.Fast
        locked -> LedMode.Solid
        expr.isNotEmpty() -> LedMode.Slow
        else -> LedMode.Off
    }
    val status = when {
        error != null -> "ERR"
        locked -> "LOCK"
        expr.isNotEmpty() -> "INPUT"
        else -> "STANDBY"
    }
    val big = when {
        error != null -> "ERROR"
        preview != null -> preview
        expr.isNotEmpty() -> "···"
        else -> "0"
    }
    val amber = FieldColors.SignalAmber
    val lastChar = expr.takeLast(1)

    FieldScreen {
        StatusHeader(
            title = "TALLY",
            led = led,
            status = status,
            left = "EXPR ${expr.length}/$MAX_EXPR",
            right = (if (rad) "RAD" else "DEG") + " / 12-DIG",
        )

        RegisterPanel {
            PanelTitle(led, if (error != null) "FAULT" else if (locked) "HOLD" else "LIVE")
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                FieldText(
                    if (error != null) "ERR / $error / EDIT TO FIX" else expr.ifEmpty { "NO INPUT" },
                    10.sp, Modifier.fillMaxWidth(),
                    color = if (error != null) amber else FieldColors.Dim,
                    tracking = 1.sp, align = TextAlign.End,
                )
                FieldText(
                    big, 22.sp, Modifier.fillMaxWidth().padding(top = 4.dp),
                    weight = FontWeight.Black,
                    color = if (error != null) amber else FieldColors.Paper,
                    align = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            FieldText(
                "UPTIME $uptime / KEYS " + String.format(Locale.ROOT, "%04d", keys % 10000),
                10.sp, Modifier.padding(top = 12.dp), color = amber, tracking = 1.sp,
            )
        }

        SectionHeader("CONTROL")
        FieldToggleRow("ADVANCED PANEL", "scientific function keys", sci) { sci = !sci }
        FieldToggleRow("RAD MODE", "trig input in radians", rad) { rad = !rad }

        if (sci) {
            SectionHeader("FUNCTIONS")
            KeyGrid(SCI_KEYS.chunked(5)) { (insert, label) ->
                FieldKey(label, { input(insert) }, Modifier.weight(1f), height = 40.dp, size = 12.sp)
            }
        }

        SectionHeader("KEYPAD")
        KeyGrid(MAIN_ROWS) { label ->
            FieldKey(
                label = label,
                // A lambda per key. The original evaluated clr()/del()/input()
                // during composition and passed their Unit result (a compile error).
                onPress = {
                    when (label) {
                        "CLR" -> clear()
                        "DEL" -> del()
                        else -> input(label)
                    }
                },
                modifier = Modifier.weight(1f),
                variant = if (label == "CLR") KeyVariant.Ink else KeyVariant.Paper,
                active = label in OPS && lastChar == label,
            )
        }
        FieldKey("=", { run() }, Modifier.fillMaxWidth(), variant = KeyVariant.Accent)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("LOG", Modifier.weight(1f))
            if (log.isNotEmpty()) {
                FieldKey("CLR LOG", { log.clear(); LogStore.save(context, log) }, Modifier.width(72.dp), height = 26.dp, size = 10.sp)
            }
        }
        if (log.isEmpty()) {
            EmptyCard("NO ENTRIES", "RUN = TO LOG AN ENTRY. TAP A ROW TO RECALL.")
        } else {
            log.forEachIndexed { i, (e, r) ->
                FieldRow(index = i + 1, selected = false, onClick = { recall(r) }, trailing = "= $r") {
                    FieldText(e, 12.sp, tracking = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        SkinLine("FIELD DESIGN LANGUAGE / SKIN: SIGNAL AMBER / NO EXTENSIONS")
    }
}

@Composable
private fun PanelTitle(led: LedMode, state: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BlinkingLed(led)
            FieldText("REGISTER", 16.sp, color = FieldColors.Paper)
        }
        FieldText(state, 10.sp, color = FieldColors.Dim, tracking = 1.sp)
    }
}

@Composable
private fun <T> KeyGrid(rows: List<List<T>>, key: @Composable RowScope.(T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key(it) }
            }
        }
    }
}
