package dev.field.list

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/**
 * A space is a place lists live. Three kinds:
 * - the app's own storage ([tree] null, not [cloud]);
 * - a folder the user picked ([tree]), which a sync app may keep identical
 *   on two phones;
 * - a folder in Drime ([cloud]): the lists are kept in app storage and
 *   [ListSync] keeps them in step with that folder, which can be shared
 *   with another Drime account.
 */
data class Space(val id: String, val name: String, val tree: String?, val folderId: Long = 0, val folderHash: String = "") {
    val cloud: Boolean get() = folderHash.isNotEmpty()
}

/** Finds a list again: its space and its file ([docId]: a file name, or a document id in a picked folder). */
data class ListRef(val spaceId: String, val docId: String, val name: String) {
    val key: String get() = "$spaceId|$docId"
}

data class LoadedList(val ref: ListRef, val list: TaskList)

data class ListState(
    val spaces: List<Space> = listOf(ListStore.LOCAL),
    val lists: List<LoadedList> = emptyList(),
    /** MY DAY picks: task id -> the epoch day it was picked for. This phone's own, not in the files. */
    val picks: Map<String, Long> = emptyMap(),
    /** Spaces whose folder could not be read on the last pass. */
    val unavailable: Set<String> = emptySet(),
    /** False until the files were read once. */
    val loaded: Boolean = false,
    /** Key of the list that receives shared text and MY DAY's quick add. */
    val lastList: String = "",
    /** The last failure worth telling the user; "" when all is well. */
    val notice: String = "",
) {
    fun find(key: String): LoadedList? = lists.firstOrNull { it.ref.key == key }
    fun space(id: String): Space? = spaces.firstOrNull { it.id == id }

    /** Where a task goes when no list was named: the last list used, else the first. */
    val defaultList: LoadedList? get() = find(lastList) ?: lists.firstOrNull()
}

/**
 * LIST's storage. Every list is one Markdown file (see [TaskFile]).
 *
 * Shared lists are files that a sync app changes behind LIST's back, so:
 * - every edit reads the file fresh, applies the one change, and writes it
 *   back (a change the partner synced a moment ago is kept);
 * - conflict copies a sync tool leaves behind are merged into the original
 *   on the next read, losing nothing (see [TaskFile.merge]).
 *
 * All file work runs on one background thread, in order.
 */
object ListStore {

    val LOCAL = Space("local", "ON THIS PHONE", null)

    private const val PREFS = "list"
    private const val KEY = "state"
    private const val DIR = "lists"
    private const val MAX_BYTES = 1_000_000

    private val _state = MutableStateFlow(ListState())
    val state: StateFlow<ListState> = _state.asStateFlow()

    /** A view key to show once (after shared text was filed); the screen clears it. */
    val incoming = MutableStateFlow<String?>(null)

    private val io = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _state.value = readPrefs(context)
    }

    private fun set(context: Context, change: (ListState) -> ListState) {
        val (before, after) = synchronized(this) {
            val b = _state.value
            val a = change(b)
            _state.value = a
            b to a
        }
        val persisted = before.spaces != after.spaces || before.picks != after.picks || before.lastList != after.lastList
        if (persisted) writePrefs(context, after)
    }

    private fun newId(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..6).map { alphabet.random() }.joinToString("")
    }

    // ---- commands (return at once; the work happens on the file thread) ----

    /** Reads every list again: picks up what the sync app brought, merges conflict copies. */
    fun refresh(context: Context) {
        io.launch { refreshNow(context) }
    }

    /** Applies one change to one list, against the file as it is now. */
    fun edit(context: Context, ref: ListRef, change: (TaskList) -> TaskList) {
        io.launch { editNow(context, ref, change) }
    }

    fun addTask(context: Context, ref: ListRef, quick: QuickTask, pickForToday: Boolean = false) {
        if (quick.title.isBlank()) return
        val id = newId()
        if (pickForToday) set(context) { it.copy(picks = it.picks + (id to LocalDate.now().toEpochDay())) }
        set(context) { it.copy(lastList = ref.key) }
        edit(context, ref) { it.add(Task(id, quick.title, important = quick.important, due = quick.due)) }
    }

    /** Puts a task on, or takes it off, today's MY DAY. */
    fun togglePick(context: Context, taskId: String) = set(context) {
        val today = LocalDate.now().toEpochDay()
        val fresh = it.picks.filterValues { day -> day >= today } // old picks lapse
        it.copy(picks = if (fresh[taskId] == today) fresh - taskId else fresh + (taskId to today))
    }

    fun clearNotice(context: Context) = set(context) { it.copy(notice = "") }

    fun createList(context: Context, spaceId: String, name: String) {
        io.launch {
            val space = _state.value.space(spaceId) ?: return@launch
            val title = Tasks.clean(name)
            if (title.isEmpty()) return@launch
            val docId = create(context, space, TaskFile.fileName(title))
            if (docId == null || !write(context, space, docId, "# $title\n")) {
                set(context) { it.copy(notice = "COULD NOT CREATE THE LIST IN THAT FOLDER.") }
                return@launch
            }
            refreshNow(context)
            if (space.cloud) ListSync.soon(context)
        }
    }

    fun deleteList(context: Context, ref: ListRef) {
        io.launch {
            val space = _state.value.space(ref.spaceId) ?: return@launch
            delete(context, space, ref.docId)
            refreshNow(context)
            if (space.cloud) ListSync.soon(context)
        }
    }

    /** Adds a picked folder as a space and keeps access to it across restarts. */
    fun addSpace(context: Context, uri: Uri) {
        io.launch {
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (!granted) {
                set(context) { it.copy(notice = "COULD NOT USE THAT FOLDER. PICK ANOTHER ONE.") }
                return@launch
            }
            val tree = uri.toString()
            if (_state.value.spaces.none { it.tree == tree }) {
                val label = runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfter(':').substringAfterLast('/') }
                    .getOrDefault("").ifEmpty { "FOLDER" }
                set(context) { it.copy(spaces = it.spaces + Space("s" + newId(), label.uppercase(), tree)) }
            }
            refreshNow(context)
        }
    }

    /** Adds a Drime folder as a space. Its lists arrive with the next sync. */
    fun addCloudSpace(context: Context, name: String, folderId: Long, folderHash: String) {
        if (folderHash.isEmpty() || _state.value.spaces.any { it.folderId == folderId && it.cloud }) return
        set(context) { it.copy(spaces = it.spaces + Space("d" + newId(), name.uppercase(), null, folderId, folderHash)) }
        ListSync.now(context)
    }

    /** Forgets a space. A picked folder and a Drime folder are not touched; only this phone's copy of a Drime space goes. */
    fun removeSpace(context: Context, spaceId: String) {
        if (spaceId == LOCAL.id) return
        io.launch {
            val space = _state.value.space(spaceId)
            set(context) { it.copy(spaces = it.spaces.filter { s -> s.id != spaceId }) }
            if (space != null && space.cloud) {
                synchronized(this@ListStore) { dir(context, space).deleteRecursively() }
                ListSync.forget(context, spaceId)
            }
            refreshNow(context)
        }
    }

    // ---- for ListSync: the files of a Drime space, read and written under the same lock as every edit ----

    internal fun <T> locked(block: () -> T): T = synchronized(this) { block() }

    internal fun freshId(): String = newId()

    /** File name -> text of every list in the space. */
    internal fun mirror(context: Context, space: Space): Map<String, String>? = locked {
        files(context, space)?.mapNotNull { (docId, name) -> read(context, space, docId)?.let { name to it } }?.toMap()
    }

    internal fun mirrorRead(context: Context, space: Space, name: String): String? =
        if (localFile(context, space, name).exists()) read(context, space, name) else null

    internal fun mirrorWrite(context: Context, space: Space, name: String, text: String): Boolean = write(context, space, name, text)

    internal fun mirrorDelete(context: Context, space: Space, name: String) = delete(context, space, name)

    /** Shared text becomes a task in the default list (an "Inbox" is made if there is no list at all). */
    fun addShared(context: Context, text: String) {
        io.launch {
            if (!_state.value.loaded) refreshNow(context)
            var target = _state.value.defaultList
            if (target == null) {
                val docId = create(context, LOCAL, "Inbox.md")
                if (docId != null) write(context, LOCAL, docId, "# Inbox\n")
                refreshNow(context)
                target = _state.value.defaultList
            }
            val ref = target?.ref ?: return@launch
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val title = Tasks.clean(lines.firstOrNull().orEmpty()).take(200)
            if (title.isEmpty()) return@launch
            val id = newId()
            // Further lines of the shared text become the task's steps.
            val steps = lines.drop(1).take(20).map { Step(Tasks.clean(it).take(200)) }
            editNow(context, ref) { it.add(Task(id, title, steps = steps)) }
            set(context) { it.copy(lastList = ref.key) }
            incoming.value = "list:" + ref.key
        }
    }

    // ---- the work, on the file thread ----

    /** Blocking: for the file thread, and for the reminder receiver (which has its own thread). */
    @Synchronized
    fun refreshNow(context: Context) {
        val spaces = _state.value.spaces
        val lists = ArrayList<LoadedList>()
        val unavailable = HashSet<String>()
        for (space in spaces) {
            val files = files(context, space)
            if (files == null) {
                unavailable.add(space.id)
                continue
            }
            val names = files.map { it.second }
            val merged = HashSet<String>() // doc ids of conflict copies folded into their original
            for ((docId, name) in files) {
                val original = TaskFile.conflictOf(name, names) ?: continue
                val base = files.first { it.second == original }
                val mine = read(context, space, base.first) ?: continue
                val theirs = read(context, space, docId) ?: continue
                val result = TaskFile.merge(TaskFile.parse(mine).withIds(::newId), TaskFile.parse(theirs))
                if (write(context, space, base.first, TaskFile.format(result))) {
                    delete(context, space, docId) // if this fails the next pass merges again, which changes nothing
                    merged.add(docId)
                }
            }
            for ((docId, name) in files) {
                if (docId in merged || TaskFile.conflictOf(name, names) != null) continue
                val text = read(context, space, docId) ?: continue
                val parsed = TaskFile.parse(text)
                val list = parsed.withIds(::newId)
                if (list !== parsed) write(context, space, docId, TaskFile.format(list)) // hand-written tasks get their ids
                lists.add(LoadedList(ListRef(space.id, docId, TaskFile.listName(name)), list))
            }
        }
        val ordered = lists.sortedWith(compareBy({ l -> spaces.indexOfFirst { it.id == l.ref.spaceId } }, { it.ref.name.lowercase() }))
        set(context) { it.copy(lists = ordered, unavailable = unavailable, loaded = true) }
        Reminders.run(context, ordered)
    }

    @Synchronized
    private fun editNow(context: Context, ref: ListRef, change: (TaskList) -> TaskList) {
        val space = _state.value.space(ref.spaceId) ?: return
        val text = read(context, space, ref.docId)
        if (text == null) {
            set(context) { it.copy(notice = "COULD NOT READ \"${ref.name.uppercase()}\". IS ITS FOLDER STILL THERE?") }
            return
        }
        val current = TaskFile.parse(text).withIds(::newId)
        val next = change(current)
        if (!write(context, space, ref.docId, TaskFile.format(next))) {
            set(context) { it.copy(notice = "COULD NOT SAVE \"${ref.name.uppercase()}\".") }
            return
        }
        set(context) { s ->
            val known = s.lists.any { it.ref.key == ref.key }
            val lists = if (known) s.lists.map { if (it.ref.key == ref.key) it.copy(list = next) else it } else s.lists + LoadedList(ref, next)
            s.copy(lists = lists)
        }
        Reminders.run(context, _state.value.lists)
        if (space.cloud) ListSync.soon(context)
    }

    // ---- files: app storage or a picked folder ----

    /** Where a space without a picked folder keeps its files: one directory per Drime space, one for the phone's own. */
    private fun dir(context: Context, space: Space): File =
        File(context.filesDir, if (space.cloud) "$DIR-${space.id}" else DIR).apply { mkdirs() }
    private fun localFile(context: Context, space: Space, name: String): File = File(dir(context, space), File(name).name)
    private fun isList(name: String) = name.endsWith(".md", ignoreCase = true)
    private fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    /** (doc id, file name) of every list file in the space; null if the folder cannot be read. */
    private fun files(context: Context, space: Space): List<Pair<String, String>>? {
        val tree = space.tree?.let { Uri.parse(it) }
            ?: return (dir(context, space).listFiles { f -> f.isFile && isList(f.name) } ?: emptyArray()).map { it.name to it.name }
        return runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            val out = ArrayList<Pair<String, String>>()
            val cursor = context.contentResolver.query(children, columns, null, null, null) ?: error("no cursor")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) != Document.MIME_TYPE_DIR && isList(name)) out.add(id to name)
                }
            }
            out
        }.getOrNull()
    }

    private fun read(context: Context, space: Space, docId: String): String? = runCatching {
        val tree = space.tree?.let { Uri.parse(it) } ?: return@runCatching localFile(context, space, docId).readText()
        context.contentResolver.openInputStream(docUri(tree, docId))?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() <= MAX_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            if (out.size() > MAX_BYTES) null else String(out.toByteArray(), Charsets.UTF_8)
        }
    }.getOrNull()

    private fun write(context: Context, space: Space, docId: String, text: String): Boolean {
        val tree = space.tree?.let { Uri.parse(it) }
        if (tree == null) {
            // Temp file first, so a crash mid-write never truncates a list.
            return runCatching {
                val target = localFile(context, space, docId)
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeText(text)
                if (!temp.renameTo(target)) {
                    target.writeText(text)
                    temp.delete()
                }
            }.isSuccess
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        // "wt" truncates; a few providers only know "w", which is tried second.
        for (mode in listOf("wt", "w")) {
            val ok = runCatching { context.contentResolver.openOutputStream(docUri(tree, docId), mode)?.use { it.write(bytes) } != null }
                .getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** Creates an empty list file; the doc id of the new file, or null. A taken name gets a number. */
    private fun create(context: Context, space: Space, fileName: String): String? {
        val tree = space.tree?.let { Uri.parse(it) }
        if (tree == null) {
            val stem = fileName.substringBeforeLast('.')
            var name = fileName
            var n = 2
            while (localFile(context, space, name).exists()) name = "$stem ${n++}.md"
            return if (runCatching { localFile(context, space, name).writeText("") }.isSuccess) name else null
        }
        return runCatching {
            val parent = docUri(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(context.contentResolver, parent, "text/markdown", fileName)?.let { DocumentsContract.getDocumentId(it) }
        }.getOrNull()
    }

    private fun delete(context: Context, space: Space, docId: String) {
        val tree = space.tree?.let { Uri.parse(it) }
        if (tree == null) localFile(context, space, docId).delete()
        else runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri(tree, docId)) }
    }

    // ---- preferences: spaces, MY DAY picks, default list ----

    private fun readPrefs(context: Context): ListState {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return ListState()
        return runCatching {
            val o = JSONObject(raw)
            val spaces = ArrayList<Space>()
            spaces.add(LOCAL)
            o.optJSONArray("spaces")?.let { a ->
                for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i)
                    spaces.add(
                        Space(
                            s.getString("id"), s.getString("name"), s.optString("tree").ifEmpty { null },
                            s.optLong("folderId"), s.optString("folderHash"),
                        )
                    )
                }
            }
            val picks = HashMap<String, Long>()
            o.optJSONObject("picks")?.let { p -> for (k in p.keys()) picks[k] = p.optLong(k) }
            ListState(spaces = spaces, picks = picks, lastList = o.optString("lastList"))
        }.getOrDefault(ListState())
    }

    private fun writePrefs(context: Context, s: ListState) {
        val o = JSONObject()
        val spaces = JSONArray()
        for (space in s.spaces) {
            if (space.id == LOCAL.id) continue // built in
            spaces.put(
                JSONObject().put("id", space.id).put("name", space.name).put("tree", space.tree ?: "")
                    .put("folderId", space.folderId).put("folderHash", space.folderHash)
            )
        }
        o.put("spaces", spaces)
        val picks = JSONObject()
        for ((id, day) in s.picks) picks.put(id, day)
        o.put("picks", picks)
        o.put("lastList", s.lastList)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }
}
