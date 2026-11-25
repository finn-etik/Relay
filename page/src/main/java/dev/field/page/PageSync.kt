package dev.field.page

import android.content.Context
import android.content.SharedPreferences
import dev.field.cloud.Drime
import dev.field.cloud.DrimeEntry
import dev.field.cloud.LocalNote
import dev.field.cloud.RemoteNote
import dev.field.cloud.Sync
import dev.field.cloud.SyncAction
import dev.field.cloud.Synced
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the notes in step with a folder in the user's Drime cloud, so that
 * another PAGE (a second phone, the desktop app) sees the same notes.
 *
 * The cloud folder holds the same plain .md / .txt files as the phone,
 * nothing else: no database, no hidden state. What decides each sync is
 * [Sync.plan]; this object carries it out: it reads both sides, runs the
 * plan, and remembers how things stood afterwards. docs/SYNC.md describes
 * the whole scheme for whoever builds the other end.
 *
 * All functions block; call them from a background thread.
 */
object PageSync {

    data class Status(
        val connected: Boolean = false,
        val account: String = "",
        val running: Boolean = false,
        /** When the last sync finished without error (epoch ms), 0 = never. */
        val lastAt: Long = 0,
        /** What it did: "2 UP / 1 DOWN". */
        val summary: String = "",
        val error: String = "",
    )

    /** The folder in Drime, at the top level of the drive. */
    const val FOLDER = "PAGE"

    private const val PREFS = "page_sync" // kept out of device backups (res/xml): it holds the token
    private const val TOKEN = "token"
    private const val ACCOUNT = "account"
    private const val FOLDER_ID = "folder_id"
    private const val FOLDER_HASH = "folder_hash"
    private const val BASE = "base"
    private const val LAST_AT = "last_at"
    private const val SUMMARY = "summary"

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    private val running = AtomicBoolean(false)

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val p = prefs(context)
        _status.value = Status(
            connected = !p.getString(TOKEN, "").isNullOrEmpty(),
            account = p.getString(ACCOUNT, "").orEmpty(),
            lastAt = p.getLong(LAST_AT, 0),
            summary = p.getString(SUMMARY, "").orEmpty(),
        )
    }

    /**
     * Checks [token] with Drime, finds or creates the PAGE folder there and
     * remembers both. Returns null when connected, or what went wrong.
     */
    fun connect(context: Context, token: String): String? {
        val clean = token.trim()
        if (clean.isEmpty()) return "PASTE THE TOKEN FIRST"
        return try {
            val drime = Drime(clean)
            val account = drime.account()
            val folder = drime.list(null).firstOrNull { it.folder && it.name == FOLDER } ?: drime.createFolder(FOLDER)
            prefs(context).edit()
                .putString(TOKEN, clean).putString(ACCOUNT, account)
                .putLong(FOLDER_ID, folder.id).putString(FOLDER_HASH, folder.hash)
                .remove(BASE).remove(LAST_AT).remove(SUMMARY)
                .apply()
            _status.value = Status(connected = true, account = account)
            null
        } catch (e: Exception) {
            words(e)
        }
    }

    /** Forgets the token and the sync memory. Notes stay where they are, on both sides. */
    fun disconnect(context: Context) {
        prefs(context).edit().clear().apply()
        _status.value = Status()
    }

    /** One sync. Does nothing if one is already running or PAGE is not connected. */
    fun run(context: Context) {
        val p = prefs(context)
        val token = p.getString(TOKEN, "").orEmpty()
        if (token.isEmpty() || !running.compareAndSet(false, true)) return
        _status.value = _status.value.copy(running = true, error = "")
        try {
            val done = sync(context, p, Drime(token))
            val now = System.currentTimeMillis()
            val summary = Sync.summary(done)
            p.edit().putLong(LAST_AT, now).putString(SUMMARY, summary).apply()
            _status.value = _status.value.copy(running = false, lastAt = now, summary = summary, error = "")
        } catch (e: Exception) {
            _status.value = _status.value.copy(running = false, error = words(e))
        } finally {
            running.set(false)
        }
    }

    private fun sync(context: Context, p: SharedPreferences, drime: Drime): List<SyncAction> {
        val folderId = p.getLong(FOLDER_ID, 0)
        val folderHash = p.getString(FOLDER_HASH, "").orEmpty()

        // A folder that was deleted in Drime would otherwise look like "every note was deleted there".
        if (drime.list(null).none { it.folder && it.id == folderId }) {
            throw IOException("THE $FOLDER FOLDER IN DRIME IS GONE. DISCONNECT AND CONNECT AGAIN TO START A NEW ONE.")
        }

        PageStore.flush(context)
        val notes = PageStore.snapshot(context) ?: throw IOException("THE NOTES FOLDER ON THIS PHONE CANNOT BE READ.")
        val local = LinkedHashMap<String, PageStore.Note>()
        for (n in notes) if (isNote(n.name)) local.putIfAbsent(n.name, n)
        val remote = drime.list(folderHash).filter { !it.folder && isNote(it.name) }
        val byId = remote.associateBy { it.id }
        val memory = HashMap(readBase(p))

        val plan = Sync.plan(
            local.values.map { LocalNote(it.name, Sync.hash(it.text)) },
            remote.map { RemoteNote(it.name, it.id, stamp(it)) },
            memory,
        )
        Sync.refusal(plan, local.size, remote.size)?.let { throw IOException(it) }

        fun fetch(id: Long): String {
            val entry = byId.getValue(id)
            return String(drime.download(entry.hash), Charsets.UTF_8).removePrefix("﻿").replace("\r\n", "\n")
        }

        fun save(name: String, text: String) {
            if (!PageStore.put(context, name, text)) throw IOException("COULD NOT WRITE ${name.uppercase()} ON THIS PHONE.")
        }

        val uploaded = HashMap<String, Long>()
        val done = ArrayList<SyncAction>()
        for (action in plan) {
            val name = action.name
            when (action) {
                is SyncAction.Upload -> {
                    val note = local.getValue(name)
                    val entry = drime.upload(folderId, name, note.text.toByteArray(Charsets.UTF_8), note.modified)
                    // Whether Drime replaces a file of the same name or keeps both is not documented: handle both.
                    drime.trash(listOfNotNull(action.replaces).filter { it != entry.id })
                    if (entry.name != name) runCatching { drime.rename(entry.id, name) }
                    memory[name] = Synced(Sync.hash(note.text), entry.id, stamp(entry))
                    uploaded[name] = entry.id
                }
                is SyncAction.Download -> {
                    val text = fetch(action.id)
                    save(name, text)
                    memory[name] = Synced(Sync.hash(text), action.id, stamp(byId.getValue(action.id)))
                }
                is SyncAction.Conflict -> {
                    val mine = local.getValue(name)
                    val theirs = fetch(action.id)
                    if (Sync.hash(theirs) != Sync.hash(mine.text)) {
                        // Both texts are kept: the cloud's under the name, this phone's beside it.
                        save(Sync.conflictName(name, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))), mine.text)
                        save(name, theirs)
                        done += action
                    }
                    memory[name] = Synced(Sync.hash(theirs), action.id, stamp(byId.getValue(action.id)))
                }
                is SyncAction.DeleteLocal -> {
                    PageStore.delete(context, local.getValue(name).id)
                    memory.remove(name)
                }
                is SyncAction.DeleteRemote -> {
                    drime.trash(listOf(action.id))
                    memory.remove(name)
                }
                is SyncAction.Forget -> memory.remove(name)
            }
            if (action !is SyncAction.Conflict && action !is SyncAction.Forget) done += action
            writeBase(p, memory) // after every step: an interrupted sync resumes, it does not start over
        }

        // An upload's answer does not carry the file's final modified time; read it back, or the
        // next sync would take our own upload for somebody else's change.
        if (uploaded.isNotEmpty()) {
            val after = drime.list(folderHash).associateBy { it.id }
            for ((name, id) in uploaded) {
                val entry = after[id] ?: continue
                memory[name]?.let { memory[name] = it.copy(stamp = stamp(entry)) }
            }
            writeBase(p, memory)
        }
        return done
    }

    /** Which files are notes. */
    private fun isNote(name: String): Boolean =
        !name.startsWith(".") && listOf(".md", ".txt", ".markdown").any { name.endsWith(it, ignoreCase = true) }

    private fun stamp(e: DrimeEntry) = "${e.updated}/${e.size}"

    private fun readBase(p: SharedPreferences): Map<String, Synced> = try {
        val json = JSONObject(p.getString(BASE, "{}") ?: "{}")
        json.keys().asSequence().associateWith { name ->
            val o = json.getJSONObject(name)
            Synced(o.getString("h"), o.getLong("i"), o.getString("s"))
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeBase(p: SharedPreferences, base: Map<String, Synced>) {
        val json = JSONObject()
        for ((name, s) in base) json.put(name, JSONObject().put("h", s.hash).put("i", s.remoteId).put("s", s.stamp))
        p.edit().putString(BASE, json.toString()).apply()
    }

    private fun words(e: Exception): String = (e.message ?: "SYNC FAILED").uppercase()
}
