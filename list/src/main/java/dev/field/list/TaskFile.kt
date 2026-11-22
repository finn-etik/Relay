package dev.field.list

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A list on disk is a Markdown checklist, readable and editable anywhere:
 *
 *     # Groceries
 *
 *     - [ ] Buy paint ! due:2026-11-25 remind:2026-11-25T09:00 id:k3f9aa
 *       - [x] measure the wall
 *       - [ ] pick a colour
 *     - [x] Return the ladder id:p20c1x
 *
 * A task is a top-level checkbox line; indented checkbox lines under it are
 * its steps. After the title come optional markers, in any order: `!`
 * (important), `due:` a date, `remind:` a date and time, `id:` the task's
 * identity. Lines LIST does not understand are kept where they were. Pure.
 */
object TaskFile {
    private val taskLine = Regex("^- \\[([ xX])] ?(.*)$")
    private val stepLine = Regex("^\\s+- \\[([ xX])] ?(.*)$")
    private val dueToken = Regex("^due:(\\d{4}-\\d{2}-\\d{2})$")
    private val remindToken = Regex("^remind:(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2})$")
    private val idToken = Regex("^id:([a-z0-9]{4,12})$")

    fun parse(text: String): TaskList {
        val preamble = ArrayList<String>()
        val tasks = ArrayList<Task>()
        for (raw in text.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            val task = taskLine.matchEntire(line)
            val step = if (task == null) stepLine.matchEntire(line) else null
            when {
                task != null -> tasks.add(parseTask(task.groupValues[1] != " ", task.groupValues[2]))
                tasks.isEmpty() -> preamble.add(line)
                step != null -> {
                    val last = tasks.removeAt(tasks.size - 1)
                    tasks.add(last.copy(steps = last.steps + Step(Tasks.clean(step.groupValues[2]), step.groupValues[1] != " ")))
                }
                line.isNotBlank() -> {
                    val last = tasks.removeAt(tasks.size - 1)
                    tasks.add(last.copy(extra = last.extra + line))
                }
            }
        }
        while (preamble.isNotEmpty() && preamble.last().isBlank()) preamble.removeAt(preamble.size - 1)
        return TaskList(preamble, tasks)
    }

    private fun parseTask(done: Boolean, rest: String): Task {
        var id = ""
        var important = false
        var due: LocalDate? = null
        var remindAt: LocalDateTime? = null
        val title = ArrayList<String>()
        for (word in rest.split(' ').filter { it.isNotEmpty() }) {
            val d = dueToken.matchEntire(word)
            val r = remindToken.matchEntire(word)
            val i = idToken.matchEntire(word)
            when {
                word == "!" -> important = true
                // A marker that is not a real date stays part of the title rather than vanishing.
                d != null -> runCatching { LocalDate.parse(d.groupValues[1]) }.getOrNull()?.let { due = it } ?: title.add(word)
                r != null -> runCatching { LocalDateTime.parse(r.groupValues[1]) }.getOrNull()?.let { remindAt = it } ?: title.add(word)
                i != null -> id = i.groupValues[1]
                else -> title.add(word)
            }
        }
        return Task(id, title.joinToString(" "), done, important, due, remindAt)
    }

    fun format(list: TaskList): String {
        val out = StringBuilder()
        for (line in list.preamble) out.append(line).append('\n')
        if (list.preamble.isNotEmpty() && list.tasks.isNotEmpty()) out.append('\n')
        for (t in list.tasks) {
            out.append("- [").append(if (t.done) 'x' else ' ').append("] ").append(t.title)
            if (t.important) out.append(" !")
            t.due?.let { out.append(" due:").append(it) }
            t.remindAt?.let { out.append(" remind:").append(it.withSecond(0).withNano(0).toString().take(16)) }
            if (t.id.isNotEmpty()) out.append(" id:").append(t.id)
            out.append('\n')
            for (s in t.steps) out.append("  - [").append(if (s.done) 'x' else ' ').append("] ").append(s.text).append('\n')
            for (e in t.extra) out.append(e).append('\n')
        }
        return out.toString()
    }

    /**
     * Folds another copy of the same list into [base]. Sync tools make such
     * copies ("conflict" files) when both phones changed a list before they
     * could sync. Nothing is lost: a task only in [other] is added; for a
     * task in both, ticked wins over unticked (for the task and each step),
     * steps and markers present on only one side are kept, and where both
     * sides set a different value [base] decides.
     */
    fun merge(base: TaskList, other: TaskList): TaskList {
        val theirs = other.tasks.filter { it.id.isNotEmpty() }.associateBy { it.id }
        val merged = base.tasks.map { mine ->
            val t = theirs[mine.id] ?: return@map mine
            val steps = mine.steps.map { s -> s.copy(done = s.done || t.steps.any { it.text == s.text && it.done }) } +
                t.steps.filter { s -> mine.steps.none { it.text == s.text } }
            mine.copy(
                done = mine.done || t.done,
                important = mine.important || t.important,
                due = mine.due ?: t.due,
                remindAt = mine.remindAt ?: t.remindAt,
                steps = steps,
                extra = mine.extra + t.extra.filter { it !in mine.extra },
            )
        }
        val known = base.tasks.map { it.id }.toSet()
        // Tasks without an id cannot be matched: keep them unless the same line is already there.
        val added = other.tasks.filter { t ->
            if (t.id.isNotEmpty()) t.id !in known else base.tasks.none { it.title == t.title }
        }
        return base.copy(tasks = added + merged)
    }

    /**
     * If [fileName] is a sync tool's conflict copy of one of [names], the
     * name of the original; else null. Covers Syncthing
     * ("List.sync-conflict-20261122-101500-ABC1234.md") and the
     * "List (conflicted copy …).md" / "List (conflict …).md" styles.
     */
    fun conflictOf(fileName: String, names: Collection<String>): String? {
        if (!fileName.contains("conflict", ignoreCase = true)) return null
        val stem = fileName.substringBeforeLast('.')
        return names.filter { it != fileName && !it.contains("conflict", ignoreCase = true) }
            .filter { original ->
                val o = original.substringBeforeLast('.')
                stem.startsWith("$o.sync-conflict-") || stem.startsWith("$o (")
            }
            .maxByOrNull { it.length }
    }

    /** A file name for a list called [name]: readable, and safe on every file system. */
    fun fileName(name: String): String {
        val kept = name.map { c -> if (c.isLetterOrDigit() || c in " -_'&+,()") c else ' ' }.joinToString("")
        val stem = Tasks.clean(kept).take(48).trim()
        return (if (stem.isEmpty()) "List" else stem) + ".md"
    }

    /** "Groceries.md" -> "Groceries" */
    fun listName(fileName: String): String = fileName.substringBeforeLast('.')
}
