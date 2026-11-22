package dev.field.page

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One row of the library. [name] is the file name; [id] finds the file again (see PageStore). */
data class DocInfo(val id: String, val name: String, val title: String, val words: Int, val modified: Long)

/**
 * PAGE's storage. Notes are plain UTF-8 .md / .txt files in a folder the
 * user picks; settings and goals are one JSON value in preferences.
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

    /** A note that arrived from outside (shared text, a REEL transcript): the screen opens it, then clears this. */
    val incoming = MutableStateFlow<String?>(null)

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
    //
    // Notes are plain .md / .txt files. Once the user has picked a folder
    // (Settings.folder, a Storage Access Framework tree) they live there, as
    // ordinary files other apps can open, sync and move; a note's id is then
    // its document id. Until a folder is picked they sit in app storage and
    // the id is the file name.

    private val extensions = listOf(".md", ".txt", ".markdown")
    private fun isDoc(name: String) = extensions.any { name.endsWith(it, ignoreCase = true) }

    private fun privateDir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Only a bare name: no separators, always inside docs/. */
    private fun privateFile(context: Context, id: String): File = File(privateDir(context), File(id).name)

    private fun tree(): Uri? = _state.value.settings.folder?.let { Uri.parse(it) }
    private fun docUri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** "Documents/PAGE" for the header, or null while notes are in app storage. */
    fun folderLabel(): String? {
        val tree = tree() ?: return null
        return runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "STORAGE ROOT" } }
            .getOrDefault("FOLDER")
    }

    private fun info(id: String, name: String, modified: Long, text: String) =
        DocInfo(id, name, Text.title(text), Text.words(text), modified)

    /** Newest first. Null = the chosen folder cannot be read (removed, or access withdrawn). */
    fun list(context: Context): List<DocInfo>? {
        val tree = tree()
        if (tree == null) {
            return (privateDir(context).listFiles { f -> f.isFile && isDoc(f.name) } ?: emptyArray())
                .map { f -> info(f.name, f.name, f.lastModified(), runCatching { f.readText() }.getOrDefault("")) }
                .sortedByDescending { it.modified }
        }
        return runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE,
            )
            val out = ArrayList<DocInfo>()
            val cursor = context.contentResolver.query(children, columns, null, null, null) ?: error("no cursor")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(3) == Document.MIME_TYPE_DIR || !isDoc(name)) continue
                    out.add(info(id, name, c.getLong(2), readUri(context, docUri(tree, id)) ?: ""))
                }
            }
            out.sortedByDescending { it.modified }
        }.getOrNull()
    }

    /** Null = unreadable. The editor must not open (and later overwrite) a note it could not read. */
    fun read(context: Context, id: String): String? {
        val tree = tree()
        return if (tree != null) readUri(context, docUri(tree, id))
        else runCatching { privateFile(context, id).readText() }.getOrNull()
    }

    /** The file name of a note, e.g. "Chapter One.md". */
    private fun name(context: Context, id: String): String? {
        val tree = tree() ?: return id
        return runCatching {
            context.contentResolver.query(docUri(tree, id), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private fun createInTree(context: Context, tree: Uri, name: String): String? = runCatching {
        val parent = docUri(tree, DocumentsContract.getTreeDocumentId(tree))
        val mime = if (name.endsWith(".txt", ignoreCase = true)) "text/plain" else "text/markdown"
        // The provider picks a free name ("untitled (1).md") if this one is taken.
        DocumentsContract.createDocument(context.contentResolver, parent, mime, name)?.let { DocumentsContract.getDocumentId(it) }
    }.getOrNull()

    private fun freePrivateName(context: Context, stem: String, suffix: String): String {
        var name = stem + suffix
        var n = 2
        while (privateFile(context, name).exists()) name = "$stem-${n++}$suffix"
        return name
    }

    /** A new note, named "untitled" until it has a first line. Null = could not be created. */
    @Synchronized
    fun create(context: Context, text: String = ""): String? {
        val suffix = _state.value.settings.ext.suffix
        val tree = tree()
        val id = if (tree != null) {
            createInTree(context, tree, "untitled$suffix") ?: return null
        } else {
            freePrivateName(context, "untitled", suffix)
        }
        if (tree != null && text.isEmpty()) return id
        return if (write(context, id, text)) id else null
    }

    @Synchronized
    private fun write(context: Context, id: String, text: String): Boolean {
        val tree = tree()
        if (tree != null) return writeUri(context, docUri(tree, id), text)
        // Temp file first, so a crash mid-write never truncates a note.
        return runCatching {
            val target = privateFile(context, id)
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }.isSuccess
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        if (pending?.first == id) pending = null
        val tree = tree()
        if (tree != null) runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri(tree, id)) }
        else privateFile(context, id).delete()
    }

    /**
     * Called when the editor closes: saves, drops a note left empty, and
     * gives a still-"untitled" file its title as a name. A file that already
     * has a real name is never renamed.
     */
    @Synchronized
    fun settle(context: Context, id: String, text: String) {
        flush(context)
        if (text.isBlank()) {
            delete(context, id)
            return
        }
        val current = name(context, id) ?: return
        val stem = Text.fileName(Text.title(text))
        if (!Text.isPlaceholderName(current) || stem == "untitled") return
        val suffix = "." + current.substringAfterLast('.', "md")
        val tree = tree()
        if (tree != null) {
            runCatching { DocumentsContract.renameDocument(context.contentResolver, docUri(tree, id), stem + suffix) }
        } else {
            privateFile(context, id).renameTo(privateFile(context, freePrivateName(context, stem, suffix)))
        }
    }

    /**
     * Makes [uri] (from the system folder picker) the notes folder, keeps the
     * access across restarts, and moves any notes still in app storage into
     * it. A note is removed from app storage only after its copy was written.
     */
    @Synchronized
    fun useFolder(context: Context, uri: Uri): Boolean {
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.isSuccess
        if (!granted) return false
        flush(context)
        update(context) { it.copy(settings = it.settings.copy(folder = uri.toString())) }
        for (f in privateDir(context).listFiles { f -> f.isFile && isDoc(f.name) } ?: emptyArray()) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val id = createInTree(context, uri, f.name) ?: continue
            if (writeUri(context, docUri(uri, id), text)) f.delete()
        }
        return true
    }

    // ---- autosave: the editor stages every change, flush() writes the latest ----

    private var pending: Pair<String, String>? = null

    @Synchronized
    fun stage(id: String, text: String) {
        pending = id to text
    }

    /** Writes the staged text; on failure it stays staged for the next attempt. */
    @Synchronized
    fun flush(context: Context) {
        val (id, text) = pending ?: return
        pending = null
        if (!write(context, id, text)) pending = id to text
    }

    // ---- import / export through the system pickers ----

    /** Text of a picked document, or null if unreadable or too large. */
    fun readUri(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_IMPORT + 1)
            if (bytes.size > MAX_IMPORT) null else String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
        }
    }.getOrNull()

    /** "wt" truncates; a few providers only know "w", which is tried second. */
    fun writeUri(context: Context, uri: Uri, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (mode in listOf("wt", "w")) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri, mode)?.use { it.write(bytes) } != null }
                .getOrDefault(false)
            if (ok) return true
        }
        return false
    }

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
                folder = o.optString("folder").ifEmpty { null },
                ext = o.enumOr("ext", d.ext),
            )
            val days = HashMap<Long, Int>()
            o.optJSONObject("days")?.let { j ->
                for (k in j.keys()) k.toLongOrNull()?.let { day -> days[day] = j.optInt(k) }
            }
            val sprint = o.optJSONObject("sprint")?.let {
                Sprint(it.getInt("minutes"), it.getLong("endAt"), it.optInt("words"), it.optBoolean("external"))
            }
            val log = ArrayList<SprintLog>()
            o.optJSONArray("log")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.getJSONObject(i)
                    log.add(SprintLog(e.getInt("minutes"), e.getInt("words"), e.getLong("endedAt")))
                }
            }
            val target = o.optInt("target", Goals().target).coerceAtLeast(0)
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
        s.settings.folder?.let { o.put("folder", it) }
        o.put("ext", s.settings.ext.name)
        o.put("target", s.goals.target)
        val days = JSONObject()
        for ((day, words) in s.goals.days) days.put(day.toString(), words)
        o.put("days", days)
        s.goals.sprint?.let {
            o.put("sprint", JSONObject().put("minutes", it.minutes).put("endAt", it.endAt).put("words", it.words).put("external", it.external))
        }
        val log = JSONArray()
        for (e in s.goals.log) {
            log.put(JSONObject().put("minutes", e.minutes).put("words", e.words).put("endedAt", e.endedAt))
        }
        o.put("log", log)
        return o.toString()
    }
}
