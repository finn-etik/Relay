package dev.field.list

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TasksTest {

    private val sun = LocalDate.of(2026, 11, 22) // a Sunday
    private fun t(id: String, title: String = id, done: Boolean = false, important: Boolean = false, due: LocalDate? = null) =
        Task(id, title, done, important, due)

    @Test fun addToggleRenameRemove() {
        var l = TaskList().add(t("a", "First")).add(t("b", "Second"))
        assertEquals(listOf("b", "a"), l.tasks.map { it.id }) // newest on top
        assertSame(l, l.add(t("c", "  ")))                    // blank is ignored
        l = l.toggle("a").rename("b", "  Second   thing ")
        assertEquals(true, l.tasks[1].done)
        assertEquals("Second thing", l.tasks[0].title)
        assertEquals(1, l.open)
        assertSame(l, l.toggle("nope"))
        assertEquals(listOf("b"), l.clearDone().tasks.map { it.id })
        assertEquals(listOf("a"), l.remove("b").tasks.map { it.id })
    }

    @Test fun steps() {
        var l = TaskList().add(t("a")).addStep("a", " measure  wall ").addStep("a", "pick colour").addStep("a", " ")
        assertEquals(listOf(Step("measure wall"), Step("pick colour")), l.tasks[0].steps)
        l = l.toggleStep("a", 0)
        assertEquals(1, l.tasks[0].stepsDone)
        assertSame(l, l.toggleStep("a", 9))
        assertEquals(listOf(Step("pick colour")), l.removeStep("a", 0).tasks[0].steps)
    }

    @Test fun displayOrder() {
        val tasks = listOf(
            t("plain"), t("done", done = true), t("later", due = sun.plusDays(5)),
            t("star", important = true), t("soon", due = sun), t("doneStar", done = true, important = true),
        )
        assertEquals(listOf("star", "soon", "later", "plain", "done", "doneStar"), Tasks.ordered(tasks).map { it.id })
    }

    @Test fun myDayIsPicksPlusDue() {
        val picks = mapOf("picked" to sun.toEpochDay(), "old" to sun.minusDays(1).toEpochDay())
        assertEquals(true, Tasks.inMyDay(t("picked"), picks, sun))
        assertEquals(false, Tasks.inMyDay(t("old"), picks, sun))                       // yesterday's pick lapsed
        assertEquals(true, Tasks.inMyDay(t("x", due = sun), picks, sun))
        assertEquals(true, Tasks.inMyDay(t("x", due = sun.minusDays(3)), picks, sun))  // overdue
        assertEquals(false, Tasks.inMyDay(t("x", due = sun.plusDays(1)), picks, sun))
        assertEquals(false, Tasks.inMyDay(t("picked", done = true), picks, sun))
        assertEquals(true, Tasks.isOverdue(t("x", due = sun.minusDays(1)), sun))
        assertEquals(false, Tasks.isOverdue(t("x", due = sun), sun))
    }

    @Test fun dueLabels() {
        assertEquals("TODAY", Tasks.dueLabel(sun, sun))
        assertEquals("TOMORROW", Tasks.dueLabel(sun.plusDays(1), sun))
        assertEquals("YESTERDAY", Tasks.dueLabel(sun.minusDays(1), sun))
        assertEquals("FRI 27 NOV", Tasks.dueLabel(LocalDate.of(2026, 11, 27), sun))
        assertEquals("FRI 1 JAN 2027", Tasks.dueLabel(LocalDate.of(2027, 1, 1), sun))
    }

    @Test fun quickAdd() {
        assertEquals(QuickTask("Buy milk", null, false), Tasks.quick("  Buy   milk ", sun))
        assertEquals(QuickTask("Buy milk", sun.plusDays(1), false), Tasks.quick("Buy milk tomorrow", sun))
        assertEquals(QuickTask("Call Eva", LocalDate.of(2026, 11, 27), true), Tasks.quick("Call Eva fri !", sun))
        assertEquals(QuickTask("Call Eva", LocalDate.of(2026, 11, 27), true), Tasks.quick("Call Eva ! fre", sun))
        assertEquals(QuickTask("Städa", sun, false), Tasks.quick("Städa idag", sun))
        assertEquals(QuickTask("Plan", LocalDate.of(2026, 11, 29), false), Tasks.quick("Plan sun", sun)) // next Sunday, not today
        assertEquals(QuickTask("today", null, false), Tasks.quick("today", sun))          // a lone word is a title
        assertEquals(QuickTask("Do it today now", null, false), Tasks.quick("Do it today now", sun)) // only at the end
    }

    @Test fun remindersFireOnceAndOnlyForOpenTasks() {
        val at = LocalDateTime.of(2026, 11, 22, 9, 0)
        val a = Task("a", "A", remindAt = at)
        val b = Task("b", "B", remindAt = at.plusHours(2))
        val c = Task("c", "C", done = true, remindAt = at)
        val all = listOf(a, b, c)
        assertEquals(listOf(a), Tasks.dueReminders(all, at.plusMinutes(1), emptySet()))
        assertEquals(emptyList<Task>(), Tasks.dueReminders(all, at.plusMinutes(1), setOf(Tasks.reminderKey(a))))
        assertEquals(at.plusHours(2), Tasks.nextReminder(all, at.plusMinutes(1)))
        assertNull(Tasks.nextReminder(all, at.plusHours(3)))
        // Moving the reminder makes it a new one.
        val moved = a.copy(remindAt = at.plusMinutes(30))
        assertEquals(listOf(moved), Tasks.dueReminders(listOf(moved), at.plusHours(1), setOf(Tasks.reminderKey(a))))
    }

    @Test fun idsAreGivenOnlyWhereMissing() {
        var n = 0
        val l = TaskList(tasks = listOf(t("keep"), t(""), t(""))).withIds { "new" + (++n) }
        assertEquals(listOf("keep", "new1", "new2"), l.tasks.map { it.id })
        assertSame(l, l.withIds { "x" })
    }
}
