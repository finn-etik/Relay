package dev.field.page

import dev.field.page.SyncAction.Conflict
import dev.field.page.SyncAction.DeleteLocal
import dev.field.page.SyncAction.DeleteRemote
import dev.field.page.SyncAction.Download
import dev.field.page.SyncAction.Forget
import dev.field.page.SyncAction.Upload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTest {
    private fun l(name: String, hash: String = "h1") = LocalNote(name, hash)
    private fun r(name: String, id: Long = 1, stamp: String = "s1") = RemoteNote(name, id, stamp)
    private val synced = Synced("h1", 1, "s1")

    private fun plan(local: LocalNote?, remote: RemoteNote?, base: Synced?) =
        Sync.plan(listOfNotNull(local), listOfNotNull(remote), if (base == null) emptyMap() else mapOf("a.md" to base))

    @Test fun inStepMeansNothingToDo() {
        assertTrue(plan(l("a.md"), r("a.md"), synced).isEmpty())
        assertTrue(Sync.plan(emptyList(), emptyList(), emptyMap()).isEmpty())
    }

    @Test fun theSideThatChangedWins() {
        assertEquals(listOf(Upload("a.md", replaces = 1)), plan(l("a.md", "h2"), r("a.md"), synced))
        assertEquals(listOf(Download("a.md", 1)), plan(l("a.md"), r("a.md", stamp = "s2"), synced))
        // Replaced in the cloud by another device (a new entry under the same name) is a change too.
        assertEquals(listOf(Download("a.md", 7)), plan(l("a.md"), r("a.md", id = 7), synced))
    }

    @Test fun newNotesTravelBothWays() {
        assertEquals(listOf(Upload("a.md")), plan(l("a.md"), null, null))
        assertEquals(listOf(Download("a.md", 1)), plan(null, r("a.md"), null))
    }

    @Test fun deletionsTravelOnlyWhenTheOtherSideIsUntouched() {
        assertEquals(listOf(DeleteRemote("a.md", 1)), plan(null, r("a.md"), synced))
        assertEquals(listOf(DeleteLocal("a.md")), plan(l("a.md"), null, synced))
        // Deleted on one side, edited on the other: the edit survives.
        assertEquals(listOf(Download("a.md", 1)), plan(null, r("a.md", stamp = "s2"), synced))
        assertEquals(listOf(Upload("a.md")), plan(l("a.md", "h2"), null, synced))
        assertEquals(listOf(Forget("a.md")), plan(null, null, synced))
    }

    @Test fun changesOnBothSidesAreAConflict() {
        assertEquals(listOf(Conflict("a.md", 1)), plan(l("a.md", "h2"), r("a.md", stamp = "s2"), synced))
        // Never synced, present on both sides: could be the same text or not; the texts decide.
        assertEquals(listOf(Conflict("a.md", 1)), plan(l("a.md"), r("a.md"), null))
    }

    @Test fun everyNameIsDecidedOnItsOwn() {
        val actions = Sync.plan(
            local = listOf(l("keep.md"), l("new.md", "n"), l("edited.md", "e2")),
            remote = listOf(r("keep.md", 1), r("edited.md", 2), r("theirs.md", 3), r("theirs.md", 4)),
            base = mapOf("keep.md" to synced, "edited.md" to Synced("e1", 2, "s1"), "gone.md" to synced),
        )
        assertEquals(listOf(Upload("edited.md", 2), Forget("gone.md"), Upload("new.md"), Download("theirs.md", 3)), actions)
    }

    @Test fun aPlanThatDeletesMostNotesIsRefused() {
        val wipe = (1..6).map { DeleteLocal("n$it.md") }
        assertNotNull(Sync.refusal(wipe, localCount = 8, remoteCount = 0))
        assertNull(Sync.refusal(wipe.take(3), localCount = 4, remoteCount = 0))
        assertNull(Sync.refusal(wipe.take(4), localCount = 20, remoteCount = 0))
        assertNotNull(Sync.refusal((1..5).map { DeleteRemote("n$it.md", it.toLong()) }, localCount = 0, remoteCount = 6))
        assertNull(Sync.refusal(listOf(Upload("a.md"), Download("b.md", 1)), 1, 1))
    }

    @Test fun namesHashesAndSummaries() {
        assertEquals("Draft (conflict 2026-11-25 1432).md", Sync.conflictName("Draft.md", "2026-11-25 1432"))
        assertEquals("notes (conflict x)", Sync.conflictName("notes", "x"))
        assertTrue(Sync.isNote("A.MD") && Sync.isNote("b.txt") && !Sync.isNote("c.pdf") && !Sync.isNote(".hidden.md"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sync.hash("abc"))
        assertEquals("IN STEP", Sync.summary(emptyList()))
        assertEquals("1 UP / 2 DOWN / 1 REMOVED / 1 KEPT TWICE", Sync.summary(listOf(Upload("a"), Download("b", 1), Download("c", 2), DeleteLocal("d"), Conflict("e", 3), Forget("f"))))
    }
}
