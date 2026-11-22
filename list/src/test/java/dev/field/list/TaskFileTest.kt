package dev.field.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TaskFileTest {

    private val sample = """
        |# Groceries
        |
        |- [ ] Buy paint ! due:2026-11-25 remind:2026-11-25T09:00 id:k3f9aa
        |  - [x] measure the wall
        |  - [ ] pick a colour
        |  ask at the shop about primer
        |- [x] Return the ladder id:p20c1x
        |- [ ] Written by hand in another app
        |""".trimMargin()

    @Test fun parsesTasksStepsAndMarkers() {
        val l = TaskFile.parse(sample)
        assertEquals(listOf("# Groceries"), l.preamble)
        assertEquals(3, l.tasks.size)
        val paint = l.tasks[0]
        assertEquals(
            Task(
                "k3f9aa", "Buy paint", done = false, important = true,
                due = LocalDate.of(2026, 11, 25), remindAt = LocalDateTime.of(2026, 11, 25, 9, 0),
                steps = listOf(Step("measure the wall", true), Step("pick a colour")),
                extra = listOf("  ask at the shop about primer"),
            ),
            paint,
        )
        assertEquals(Task("p20c1x", "Return the ladder", done = true), l.tasks[1])
        assertEquals(Task("", "Written by hand in another app"), l.tasks[2])
    }

    @Test fun writingBackChangesNothing() {
        assertEquals(sample, TaskFile.format(TaskFile.parse(sample)))
        assertEquals("", TaskFile.format(TaskFile.parse("")))
        assertEquals("- [ ] a id:aaaa\n", TaskFile.format(TaskFile.parse("- [ ] a id:aaaa")))
        // Windows line endings and an upper-case X are read, and normalised on save.
        assertEquals("- [x] a\n  - [x] s\n", TaskFile.format(TaskFile.parse("- [X] a\r\n  - [X] s\r\n")))
    }

    @Test fun markersThatAreNotRealStayInTheTitle() {
        val l = TaskFile.parse("- [ ] Pay due:2026-13-45 and due:soon id:ab")
        assertEquals("Pay due:2026-13-45 and due:soon id:ab", l.tasks[0].title)
        assertNull(l.tasks[0].due)
        assertEquals("", l.tasks[0].id)
    }

    @Test fun mergeLosesNothing() {
        val mine = TaskFile.parse(
            """
            |# Home
            |
            |- [ ] Paint id:aaaa
            |  - [ ] measure
            |- [ ] Ladder due:2026-11-25 id:bbbb
            |""".trimMargin()
        )
        val theirs = TaskFile.parse(
            """
            |- [x] Paint ! id:aaaa
            |  - [x] measure
            |  - [ ] buy brushes
            |- [ ] Ladder due:2026-12-01 remind:2026-11-30T08:00 id:bbbb
            |- [ ] Call the plumber id:cccc
            |- [ ] no id yet
            |""".trimMargin()
        )
        val m = TaskFile.merge(mine, theirs)
        assertEquals(listOf("# Home"), m.preamble)
        assertEquals(listOf("cccc", "", "aaaa", "bbbb"), m.tasks.map { it.id })
        val paint = m.tasks[2]
        assertEquals(true, paint.done)       // ticked wins
        assertEquals(true, paint.important)
        assertEquals(listOf(Step("measure", true), Step("buy brushes")), paint.steps)
        val ladder = m.tasks[3]
        assertEquals(LocalDate.of(2026, 11, 25), ladder.due)                       // both set: ours stays
        assertEquals(LocalDateTime.of(2026, 11, 30, 8, 0), ladder.remindAt)        // only theirs set: kept
        // Merging the same copy again adds nothing (a conflict file that could not be deleted).
        assertEquals(m, TaskFile.merge(m, theirs))
    }

    @Test fun conflictCopiesAreRecognised() {
        val names = listOf("Home.md", "Home improvements.md", "Home.sync-conflict-20261122-101500-ABC1234.md",
            "Home improvements (conflicted copy 2026-11-22).md", "Conflict resolution.md")
        assertEquals("Home.md", TaskFile.conflictOf("Home.sync-conflict-20261122-101500-ABC1234.md", names))
        assertEquals("Home improvements.md", TaskFile.conflictOf("Home improvements (conflicted copy 2026-11-22).md", names))
        assertNull(TaskFile.conflictOf("Home.md", names))
        assertNull(TaskFile.conflictOf("Conflict resolution.md", names)) // a list that merely has the word in its name
    }

    @Test fun fileNames() {
        assertEquals("Groceries & more.md", TaskFile.fileName(" Groceries & more "))
        assertEquals("a b.md", TaskFile.fileName("a/b"))
        assertEquals("List.md", TaskFile.fileName("///"))
        assertEquals("Groceries", TaskFile.listName("Groceries.md"))
    }
}
