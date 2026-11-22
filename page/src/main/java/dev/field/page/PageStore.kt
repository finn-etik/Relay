package dev.field.page

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One row of the library. [id] is the file name. */
data class DocInfo(val id: String, val title: String, val words: Int, val modified: Long)

/**
 * PAGE's storage. Documents are plain UTF-8 Markdown files in the app's
 * `docs/` folder; settings and goals are one JSON value in preferences.
 */
object PageStore {

    private const val PREFS = "page"
    private const val KEY = "state"
    private const val DIR = "docs"
    /** Imports above this are refused rather than loaded into one text field. */
    private const val MAX_IMPORT = 2_000_000

    private val _state = MutableStateFlow(PageState())
    val state: StateFlow<PageState> = _state.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _state.value = read(context)
    }

    @Synchronized
    fun update(context: Context, change: (PageState) -> PageState) {
        val before = _state.value
        val after = change(before)
        if (after == before) return
        _state.value = after
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(after)).apply()
    }

    // ---- documents ----

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Only names this store made: no separators, always inside docs/. */
    private fun file(context: Context, id: String): File = File(dir(context), File(id).name)

    fun list(context: Context): List<DocInfo> =
        (dir(context).listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray())
            .map { f ->
                val text = runCatching { f.readText() }.getOrDefault("")
                DocInfo(f.name, Text.title(text), Text.words(text), f.lastModified())
            }
            .sortedByDescending { it.modified }

    fun read(context: Context, id: String): String =
        runCatching { file(context, id).readText() }.getOrDefault("")

    @Synchronized
    fun create(context: Context, text: String = ""): String {
        var stamp = System.currentTimeMillis()
        while (file(context, "$stamp.md").exists()) stamp++
        val id = "$stamp.md"
        write(context, id, text)
        return id
    }

    /** Writes to a temp file first, so a crash mid-write never truncates a document. */
    @Synchronized
    private fun write(context: Context, id: String, text: String) {
        val target = file(context, id)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        if (pending?.first == id) pending = null
        file(context, id).delete()
    }

    // ---- autosave: the editor stages every change, flush() writes the latest ----

    private var pending: Pair<String, String>? = null

    @Synchronized
    fun stage(id: String, text: String) {
        pending = id to text
    }

    @Synchronized
    fun flush(context: Context) {
        val (id, text) = pending ?: return
        pending = null
        runCatching { write(context, id, text) }.onFailure { pending = id to text }
    }

    // ---- import / export through the system pickers ----

    /** Text of a picked document, or null if unreadable or too large. */
    fun readUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_IMPORT + 1)
            if (bytes.size > MAX_IMPORT) null else String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
        }
    }.getOrNull()

    fun writeUri(context: Context, uri: Uri, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // ---- persistence ----

    private inline fun <reified E : Enum<E>> JSONObject.enumOr(key: String, default: E): E =
        runCatching { enumValueOf<E>(optString(key)) }.getOrDefault(default)

    private fun read(context: Context): PageState {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return PageState()
        return runCatching {
            val o = JSONObject(raw)
            val d = Settings()
            val settings = Settings(
                theme = o.enumOr("theme", d.theme),
                face = o.enumOr("face", d.face),
                size = o.optInt("size", d.size).coerceIn(0, 2),
                focus = o.enumOr("focus", d.focus),
                typewriter = o.optBoolean("typewriter", d.typewriter),
                style = o.optBoolean("style", d.style),
            )
            val days = HashMap<Long, Int>()
            o.optJSONObject("days")?.let { j ->
                for (k in j.keys()) k.toLongOrNull()?.let { day -> days[day] = j.optInt(k) }
            }
            val sprint = o.optJSONObject("sprint")?.let {
                Sprint(it.getInt("minutes"), it.getLong("endAt"), it.optInt("words"))
            }
            val log = ArrayList<SprintLog>()
            o.optJSONArray("log")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i)
                    log.add(SprintLog(e.getInt("minutes"), e.getInt("words"), e.getLong("endedAt")))
                }
            }
            val target = o.optInt("target", Goals().target).takeIf { it > 0 } ?: Goals().target
            PageState(settings, Goals(target, days, sprint, log))
        }.getOrDefault(PageState())
    }

    private fun toJson(s: PageState): String {
        val o = JSONObject()
        o.put("theme", s.settings.theme.name)
        o.put("face", s.settings.face.name)
        o.put("size", s.settings.size)
        o.put("focus", s.settings.focus.name)
        o.put("typewriter", s.settings.typewriter)
        o.put("style", s.settings.style)
        o.put("target", s.goals.target)
        val days = JSONObject()
        for ((day, words) in s.goals.days) days.put(day.toString(), words)
        o.put("days", days)
        s.goals.sprint?.let {
            o.put("sprint", JSONObject().put("minutes", it.minutes).put("endAt", it.endAt).put("words", it.words))
        }
        val log = JSONArray()
        for (e in s.goals.log) {
            log.put(JSONObject().put("minutes", e.minutes).put("words", e.words).put("endedAt", e.endedAt))
        }
        o.put("log", log)
        return o.toString()
    }
}
