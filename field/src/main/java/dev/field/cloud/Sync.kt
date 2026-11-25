package dev.field.cloud

import java.security.MessageDigest

/** A note on this device, as sync sees it: its file name and a hash of its text. */
data class LocalNote(val name: String, val hash: String)

/**
 * A note in the cloud folder. [stamp] is anything that changes when the
 * file does (PAGE uses "modified time / size"); it is only ever compared
 * for equality with the stamp remembered from the last sync.
 */
data class RemoteNote(val name: String, val id: Long, val stamp: String)

/** What both sides looked like when a note was last in step: the memory sync decides by. */
data class Synced(val hash: String, val remoteId: Long, val stamp: String)

sealed interface SyncAction {
    val name: String

    /** Send the local note. [replaces] is the cloud entry it supersedes, if any. */
    data class Upload(override val name: String, val replaces: Long? = null) : SyncAction

    /** Fetch the cloud note, creating or overwriting the local file. */
    data class Download(override val name: String, val id: Long) : SyncAction

    /** The note was deleted in the cloud and not touched here: delete it here too. */
    data class DeleteLocal(override val name: String) : SyncAction

    /** The note was deleted here and not touched in the cloud: move the cloud copy to the trash. */
    data class DeleteRemote(override val name: String, val id: Long) : SyncAction

    /** Both sides changed (or both are new): compare the texts, keep both if they differ. */
    data class Conflict(override val name: String, val id: Long) : SyncAction

    /** Gone on both sides: nothing to do but forget it. */
    data class Forget(override val name: String) : SyncAction
}

/**
 * The kit's file sync, as a decision table: PAGE's notes and LIST's shared
 * lists both follow it. It is deliberately simple so that a second
 * implementation (a desktop app) can follow the same rules; they are
 * written out for that purpose in docs/SYNC.md.
 *
 * A note (or list) is one plain text file; the file name is its identity. For every
 * name, sync compares three things: the file here, the file in the cloud
 * folder, and what it remembers of both from the last sync ([Synced]).
 * Whichever side changed since then wins; if both did, it is a conflict,
 * which the app resolves without losing either text (PAGE keeps both
 * notes, LIST merges the two lists task by task).
 *
 * Pure: no files, no network, no clock.
 */
object Sync {

    fun plan(local: List<LocalNote>, remote: List<RemoteNote>, base: Map<String, Synced>): List<SyncAction> {
        val here = local.associateBy { it.name }
        // Two cloud files of one name: the first listed stands for the name, the rest are left alone.
        val there = LinkedHashMap<String, RemoteNote>().apply { for (r in remote) putIfAbsent(r.name, r) }
        val out = ArrayList<SyncAction>()
        for (name in (here.keys + there.keys + base.keys).sorted()) {
            val l = here[name]
            val r = there[name]
            val b = base[name]
            val localChanged = l != null && l.hash != b?.hash
            val remoteChanged = r != null && (b == null || r.id != b.remoteId || r.stamp != b.stamp)
            val action: SyncAction? = when {
                l != null && r != null -> when {
                    localChanged && remoteChanged -> SyncAction.Conflict(name, r.id)
                    localChanged -> SyncAction.Upload(name, r.id)
                    remoteChanged -> SyncAction.Download(name, r.id)
                    else -> null
                }
                l != null -> if (b == null || localChanged) SyncAction.Upload(name) else SyncAction.DeleteLocal(name)
                r != null -> if (b == null || remoteChanged) SyncAction.Download(name, r.id) else SyncAction.DeleteRemote(name, r.id)
                else -> SyncAction.Forget(name)
            }
            if (action != null) out += action
        }
        return out
    }

    /**
     * A last check before anything is deleted. A plan that would delete
     * most of the notes on one side is far more likely a mistake (a wrong
     * folder, a listing cut short) than a wish. Returns why not, or null.
     */
    fun refusal(plan: List<SyncAction>, localCount: Int, remoteCount: Int): String? {
        val local = plan.count { it is SyncAction.DeleteLocal }
        val remote = plan.count { it is SyncAction.DeleteRemote }
        return when {
            local > MASS && local * 2 > localCount -> "SYNC WOULD DELETE $local OF $localCount NOTES ON THIS PHONE. NOTHING WAS CHANGED."
            remote > MASS && remote * 2 > remoteCount -> "SYNC WOULD DELETE $remote OF $remoteCount NOTES IN THE CLOUD. NOTHING WAS CHANGED."
            else -> null
        }
    }

    private const val MASS = 3

    /** The name a local text is kept under when the cloud text takes its name: "Draft (conflict 2026-11-25 1432).md". */
    fun conflictName(name: String, stamp: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val suffix = if (dot > 0) name.substring(dot) else ""
        return "$stem (conflict $stamp)$suffix"
    }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** "2 UP / 1 DOWN / 1 KEPT TWICE", or "IN STEP". */
    fun summary(done: List<SyncAction>): String {
        val parts = listOf(
            done.count { it is SyncAction.Upload } to "UP",
            done.count { it is SyncAction.Download } to "DOWN",
            done.count { it is SyncAction.DeleteLocal || it is SyncAction.DeleteRemote } to "REMOVED",
            done.count { it is SyncAction.Conflict } to "KEPT TWICE",
        ).filter { it.first > 0 }.joinToString(" / ") { "${it.first} ${it.second}" }
        return parts.ifEmpty { "IN STEP" }
    }
}
