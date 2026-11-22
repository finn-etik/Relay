package dev.field.list

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** A step (subtask) of a task. */
data class Step(val text: String, val done: Boolean = false)

/**
 * One task. [id] identifies it across edits, phones and merges; a task
 * typed by hand into the file has none until LIST first saves it.
 * [extra] keeps any other lines found under the task (notes written in
 * another app), so saving never throws someone's text away.
 */
data class Task(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val important: Boolean = false,
    val due: LocalDate? = null,
    val remindAt: LocalDateTime? = null,
    val steps: List<Step> = emptyList(),
    val extra: List<String> = emptyList(),
) {
    val stepsDone: Int get() = steps.count { it.done }
}

/** A list: the lines above the first task (usually a heading), then the tasks in file order. */
data class TaskList(val preamble: List<String> = emptyList(), val tasks: List<Task> = emptyList()) {

    val open: Int get() = tasks.count { !it.done }

    private fun change(id: String, edit: (Task) -> Task): TaskList {
        val changed = tasks.map { if (it.id == id) edit(it) else it }
        return if (changed == tasks) this else copy(tasks = changed)
    }

    /** New tasks go on top: the list reads newest first, like a stack of slips. */
    fun add(task: Task): TaskList = if (task.title.isBlank()) this else copy(tasks = listOf(task) + tasks)

    fun toggle(id: String): TaskList = change(id) { it.copy(done = !it.done) }
    fun remove(id: String): TaskList = copy(tasks = tasks.filter { it.id != id })
    fun rename(id: String, title: String): TaskList = if (title.isBlank()) this else change(id) { it.copy(title = Tasks.clean(title)) }
    fun toggleImportant(id: String): TaskList = change(id) { it.copy(important = !it.important) }
    fun setDue(id: String, due: LocalDate?): TaskList = change(id) { it.copy(due = due) }
    fun setReminder(id: String, at: LocalDateTime?): TaskList = change(id) { it.copy(remindAt = at) }

    fun addStep(id: String, text: String): TaskList =
        if (text.isBlank()) this else change(id) { it.copy(steps = it.steps + Step(Tasks.clean(text))) }

    fun toggleStep(id: String, index: Int): TaskList = change(id) { t ->
        if (index !in t.steps.indices) t
        else t.copy(steps = t.steps.mapIndexed { i, s -> if (i == index) s.copy(done = !s.done) else s })
    }

    fun removeStep(id: String, index: Int): TaskList = change(id) { t ->
        if (index !in t.steps.indices) t else t.copy(steps = t.steps.filterIndexed { i, _ -> i != index })
    }

    fun clearDone(): TaskList = copy(tasks = tasks.filter { !it.done })

    /** Gives an id to every task that has none (hand-written lines). */
    fun withIds(newId: () -> String): TaskList =
        if (tasks.none { it.id.isEmpty() }) this else copy(tasks = tasks.map { if (it.id.isEmpty()) it.copy(id = newId()) else it })
}

/** What typing into the quick-add row means. */
data class QuickTask(val title: String, val due: LocalDate?, val important: Boolean)

/** Rules that need no Android: ordering, views, quick add, reminders. Pure. */
object Tasks {

    /** One line, single spaces: a task title must stay one line of the file. */
    fun clean(text: String): String = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Display order: open before done; among open, important first, then by due date (dated before undated). */
    fun ordered(tasks: List<Task>): List<Task> =
        tasks.withIndex().sortedWith(
            compareBy<IndexedValue<Task>>({ it.value.done }, { !it.value.important && !it.value.done })
                .thenBy { if (it.value.done) 0L else it.value.due?.toEpochDay() ?: Long.MAX_VALUE }
                .thenBy { it.index }
        ).map { it.value }

    /**
     * MY DAY: tasks picked for today, plus anything due today or overdue.
     * [picks] maps a task id to the day it was picked for, so yesterday's
     * picks drop out by themselves; they are this phone's, not the list's.
     */
    fun inMyDay(task: Task, picks: Map<String, Long>, today: LocalDate): Boolean {
        if (task.done) return false
        val due = task.due
        return picks[task.id] == today.toEpochDay() || (due != null && !due.isAfter(today))
    }

    fun isOverdue(task: Task, today: LocalDate): Boolean = !task.done && task.due?.isBefore(today) == true

    /** "TODAY", "TOMORROW", "FRI 27 NOV", and "… 2027" only for another year. */
    fun dueLabel(due: LocalDate, today: LocalDate): String = when (due) {
        today -> "TODAY"
        today.plusDays(1) -> "TOMORROW"
        today.minusDays(1) -> "YESTERDAY"
        else -> {
            val day = due.dayOfWeek.name.take(3) + " " + due.dayOfMonth + " " + due.month.name.take(3)
            if (due.year == today.year) day else day + " " + due.year
        }
    }

    private val weekdays = mapOf(
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
        // Swedish
        "mån" to DayOfWeek.MONDAY, "tis" to DayOfWeek.TUESDAY, "ons" to DayOfWeek.WEDNESDAY, "tor" to DayOfWeek.THURSDAY,
        "fre" to DayOfWeek.FRIDAY, "lör" to DayOfWeek.SATURDAY, "sön" to DayOfWeek.SUNDAY,
    )

    private fun dateWord(word: String, today: LocalDate): LocalDate? = when (val w = word.lowercase()) {
        "today", "idag" -> today
        "tomorrow", "imorgon" -> today.plusDays(1)
        else -> weekdays[w]?.let { today.with(TemporalAdjusters.next(it)) }
    }

    /**
     * Quick add: a trailing "!" marks the task important, and a trailing day
     * word sets the due date: today / tomorrow (idag / imorgon), or a weekday
     * (mon … sun, mån … sön) meaning the next one. "Call Eva fri !" is an
     * important task due next Friday.
     */
    fun quick(text: String, today: LocalDate): QuickTask {
        val words = clean(text).split(' ').filter { it.isNotEmpty() }.toMutableList()
        var important = false
        var due: LocalDate? = null
        // Read markers off the end; stop at the first ordinary word.
        while (words.size > 1) {
            val last = words.last()
            val date = if (due == null) dateWord(last, today) else null
            when {
                last == "!" && !important -> important = true
                date != null -> due = date
                else -> break
            }
            words.removeAt(words.size - 1)
        }
        return QuickTask(words.joinToString(" "), due, important)
    }

    // ---- reminders ----

    /** Identifies one reminder of one task, so each fires once even if the file is read again. */
    fun reminderKey(task: Task): String = task.id + "@" + task.remindAt

    /** Open tasks whose reminder time has come and that were not announced yet. */
    fun dueReminders(tasks: List<Task>, now: LocalDateTime, announced: Set<String>): List<Task> =
        tasks.filter { t -> !t.done && t.remindAt != null && !t.remindAt.isAfter(now) && reminderKey(t) !in announced }

    /** The next reminder still ahead, or null. */
    fun nextReminder(tasks: List<Task>, now: LocalDateTime): LocalDateTime? =
        tasks.filter { !it.done }.mapNotNull { it.remindAt }.filter { it.isAfter(now) }.minOrNull()
}
