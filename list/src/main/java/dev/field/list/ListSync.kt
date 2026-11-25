package dev.field.list

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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Shared lists through Drime. A Drime space is a folder in Drime holding
 * one Markdown file per list; this phone keeps a copy of those files in app
 * storage (so LIST works offline and every tap is instant) and this object
 * keeps the two in step.
 *
 * The rules are the kit's common ones ([Sync.plan], docs/SYNC.md) with one
 * difference that makes sharing pleasant: where two people changed the same
 * list, the lists are merged task by task ([TaskFile.merge]) instead of
 * being kept twice. Two files of one name in the folder (two phones
 * uploading at the same moment) are merged the same way.
 *
 * Two people share a space either by using one Drime account on both
 * phones, or by one sharing the folder with the other's Drime account
 * ([share]); the other then finds it under "shared with me" ([folders]).
 */
object ListSync {

    data class Status(
        val connected: Boolean = false,
        val account: String = "",
        val running: Boolean = false,
        /** When the last sync finished without error (epoch ms), 0 = never. */
        val lastAt: Long = 0,
        val error: String = "",
    )

    /** Drime folders that can become spaces: the account's own top-level folders, and those shared with it. */
    class Folders(val mine: List<DrimeEntry>, val shared: List<DrimeEntry>)

    private const val PREFS = "list_sync" // kept out of device backups (res/xml): it holds the token
    private const val TOKEN = "token"
    private const val ACCOUNT = "account"
    private const val LAST_AT = "last_at"
    private const val BASE = "base:"

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    /** One thread: syncs never overlap, and a burst of edits becomes one sync. */
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun token(context: Context): String = prefs(context).getString(TOKEN, "").orEmpty()

    fun load(context: Context) {
        val p = prefs(context)
        _status.value = Status(
            connected = token(context).isNotEmpty(),
            account = p.getString(ACCOUNT, "").orEmpty(),
            lastAt = p.getLong(LAST_AT, 0),
        )
    }

    // ---- account (blocking: call from a background thread) ----

    /** Checks [token] with Drime and remembers it. Null when connected, or what went wrong. */
    fun connect(context: Context, token: String): String? {
        val clean = token.trim()
        if (clean.isEmpty()) return "PASTE THE TOKEN FIRST"
        return try {
            val account = Drime(clean).account()
            prefs(context).edit().putString(TOKEN, clean).putString(ACCOUNT, account).apply()
            _status.value = Status(connected = true, account = account)
            now(context)
            null
        } catch (e: Exception) {
            words(e)
        }
    }

    /** Forgets the token. Drime spaces stay on the phone as they are, and stop syncing. */
    fun disconnect(context: Context) {
        prefs(context).edit().remove(TOKEN).remove(ACCOUNT).apply()
        _status.value = Status()
    }

    fun folders(context: Context): Folders {
        val drime = Drime(token(context))
        return Folders(drime.list(null).filter { it.folder }, runCatching { drime.sharedFolders() }.getOrDefault(emptyList()))
    }

    /** A new folder at the top of the drive, for a new space. */
    fun createFolder(context: Context, name: String): DrimeEntry = Drime(token(context)).createFolder(name)

    /** Gives another Drime account the use of a space's folder. Null when done, or what went wrong. */
    fun share(context: Context, space: Space, email: String): String? = try {
        Drime(token(context)).share(space.folderId, listOf(email.trim()))
        null
    } catch (e: Exception) {
        words(e)
    }

    internal fun forget(context: Context, spaceId: String) = prefs(context).edit().remove(BASE + spaceId).apply()

    // ---- when to sync ----

    /** Syncs in a moment: after an edit, so that a run of taps is sent as one. */
    @Synchronized
    fun soon(context: Context) = schedule(context, 2500)

    /** Syncs as soon as the worker is free: on opening the app, on SYNC. */
    @Synchronized
    fun now(context: Context) = schedule(context, 0)

    private fun schedule(context: Context, delayMs: Long) {
        val app = context.applicationContext
        if (token(app).isEmpty()) return
        pending?.cancel(false)
        pending = worker.schedule(Runnable { run(app) }, delayMs, TimeUnit.MILLISECONDS)
    }

    // ---- the sync ----

    private fun run(context: Context) {
        val token = token(context)
        val spaces = ListStore.state.value.spaces.filter { it.cloud }
        if (token.isEmpty() || spaces.isEmpty()) return
        _status.value = _status.value.copy(running = true, error = "")
        var error = ""
        try {
            val drime = Drime(token)
            // A folder that was deleted or unshared must not read as "every list was deleted".
            val reachable = (drime.list(null) + runCatching { drime.sharedFolders() }.getOrDefault(emptyList()))
                .filter { it.folder }.map { it.id }.toSet()
            for (space in spaces) {
                try {
                    if (space.folderId !in reachable) throw IOException("THE FOLDER OF ${space.name} IS GONE FROM DRIME, OR NO LONGER SHARED WITH YOU.")
                    syncSpace(context, drime, space)
                } catch (e: Exception) {
                    error = words(e)
                }
            }
        } catch (e: Exception) {
            error = words(e)
        }
        ListStore.refreshNow(context)
        val at = if (error.isEmpty()) System.currentTimeMillis().also { prefs(context).edit().putLong(LAST_AT, it).apply() } else _status.value.lastAt
        _status.value = _status.value.copy(running = false, lastAt = at, error = error)
    }

    private fun syncSpace(context: Context, drime: Drime, space: Space) {
        val p = prefs(context)
        val local = ListStore.mirror(context, space) ?: throw IOException("COULD NOT READ THE LISTS OF ${space.name}.")
        val groups = drime.list(space.folderHash)
            .filter { !it.folder && it.name.endsWith(".md", ignoreCase = true) && !it.name.startsWith(".") }
            .groupBy { it.name }
        val memory = HashMap(readBase(p, space.id))
        val uploaded = HashMap<String, Long>()

        fun fetch(entry: DrimeEntry): String =
            String(drime.download(entry.hash, 1_000_000), Charsets.UTF_8).removePrefix("﻿").replace("\r\n", "\n")

        fun merged(mine: String, theirs: String): String =
            TaskFile.format(TaskFile.merge(TaskFile.parse(mine).withIds(ListStore::freshId), TaskFile.parse(theirs)))

        /** Sends [text] as the list [name]; the cloud files it supersedes go to the trash. */
        fun push(name: String, text: String, replaces: List<Long>) {
            val entry = drime.upload(space.folderId, name, text.toByteArray(Charsets.UTF_8), System.currentTimeMillis())
            // Whether Drime replaces a file of the same name or keeps both is not documented: handle both.
            drime.trash(replaces.filter { it != entry.id })
            if (entry.name != name) runCatching { drime.rename(entry.id, name) }
            memory[name] = Synced(Sync.hash(text), entry.id, stamp(entry))
            uploaded[name] = entry.id
        }

        /**
         * Brings the cloud's text in. [seen] is what this sync read from the
         * phone at its start; if the user has changed the list since (a tap
         * while we were on the network), their change is merged in, not lost.
         */
        fun pull(name: String, theirs: String, seen: String?, mergeAlways: Boolean = false): String = ListStore.locked {
            val current = ListStore.mirrorRead(context, space, name)
            val result = if (current == null || (current == seen && !mergeAlways)) theirs else merged(current, theirs)
            if (!ListStore.mirrorWrite(context, space, name, result)) throw IOException("COULD NOT SAVE ${name.uppercase()}.")
            result
        }

        // Two cloud files of one name: fold them all into one list.
        val twins = groups.filter { it.value.size > 1 }
        for ((name, entries) in twins) {
            var text = ""
            for (entry in entries) text = pull(name, fetch(entry), local[name], mergeAlways = true)
            push(name, text, entries.map { it.id })
            writeBase(p, space.id, memory)
        }

        val single = groups.filter { it.value.size == 1 }.mapValues { it.value.single() }
        val plan = Sync.plan(
            local.filterKeys { it !in twins }.map { LocalNote(it.key, Sync.hash(it.value)) },
            single.values.map { RemoteNote(it.name, it.id, stamp(it)) },
            memory.filterKeys { it !in twins },
        )
        Sync.refusal(plan, local.size, single.size)?.let { throw IOException(it) }

        for (action in plan) {
            val name = action.name
            when (action) {
                is SyncAction.Upload -> push(name, local.getValue(name), listOfNotNull(action.replaces))
                is SyncAction.Download -> {
                    val entry = single.getValue(name)
                    val theirs = fetch(entry)
                    pull(name, theirs, local[name])
                    // Remembered as the cloud's text: if a merge made ours differ, the next sync sends it.
                    memory[name] = Synced(Sync.hash(theirs), entry.id, stamp(entry))
                }
                is SyncAction.Conflict -> {
                    val entry = single.getValue(name)
                    val theirs = fetch(entry)
                    val result = if (Sync.hash(theirs) == Sync.hash(local.getValue(name))) theirs else pull(name, theirs, local[name], mergeAlways = true)
                    if (result != theirs) push(name, result, listOf(entry.id))
                    else memory[name] = Synced(Sync.hash(theirs), entry.id, stamp(entry))
                }
                is SyncAction.DeleteLocal -> {
                    // Deleted by the other person; unless it was changed here in this very moment.
                    ListStore.locked {
                        if (ListStore.mirrorRead(context, space, name) == local[name]) ListStore.mirrorDelete(context, space, name)
                    }
                    memory.remove(name)
                }
                is SyncAction.DeleteRemote -> {
                    drime.trash(listOf(action.id))
                    memory.remove(name)
                }
                is SyncAction.Forget -> memory.remove(name)
            }
            writeBase(p, space.id, memory) // after every step: an interrupted sync resumes
        }

        // An upload's answer lacks the file's final modified time; read it back, or the next
        // sync would take our own upload for the other person's change.
        if (uploaded.isNotEmpty()) {
            val after = drime.list(space.folderHash).associateBy { it.id }
            for ((name, id) in uploaded) {
                val entry = after[id] ?: continue
                memory[name]?.let { memory[name] = it.copy(stamp = stamp(entry)) }
            }
            writeBase(p, space.id, memory)
        }
    }

    private fun stamp(e: DrimeEntry) = "${e.updated}/${e.size}"

    private fun readBase(p: SharedPreferences, spaceId: String): Map<String, Synced> = try {
        val json = JSONObject(p.getString(BASE + spaceId, "{}") ?: "{}")
        json.keys().asSequence().associateWith { name ->
            val o = json.getJSONObject(name)
            Synced(o.getString("h"), o.getLong("i"), o.getString("s"))
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun writeBase(p: SharedPreferences, spaceId: String, base: Map<String, Synced>) {
        val json = JSONObject()
        for ((name, s) in base) json.put(name, JSONObject().put("h", s.hash).put("i", s.remoteId).put("s", s.stamp))
        p.edit().putString(BASE + spaceId, json.toString()).apply()
    }

    private fun words(e: Exception): String = (e.message ?: "SYNC FAILED").uppercase()
}
