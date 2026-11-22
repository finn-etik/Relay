package dev.field.page

enum class ThemeMode { AUTO, LIGHT, DARK }
enum class FocusMode { OFF, SENTENCE, PARAGRAPH }
enum class Face { MONO, SERIF, SANS }
enum class Ext(val suffix: String) { MD(".md"), TXT(".txt") }

data class Settings(
    val theme: ThemeMode = ThemeMode.AUTO,
    val face: Face = Face.MONO,
    /** 0 = small, 1 = medium, 2 = large. */
    val size: Int = 1,
    val focus: FocusMode = FocusMode.SENTENCE,
    val typewriter: Boolean = true,
    val style: Boolean = false,
    /** Tree URI of the shared notes folder; null = app storage until one is chosen. */
    val folder: String? = null,
    /** File type for new notes. */
    val ext: Ext = Ext.MD,
)

/** A running timed sprint. [words] is the net count written since it began. */
data class Sprint(val minutes: Int, val endAt: Long, val words: Int = 0)

data class SprintLog(val minutes: Int, val words: Int, val endedAt: Long)

/**
 * Daily word goal, streak and sprints. Pure and derived from the wall clock:
 * days are local epoch days, times are epoch milliseconds, both passed in.
 */
data class Goals(
    /** Words per day; 0 = no goal, just a counter. */
    val target: Int = 500,
    /** Local epoch day -> net words written that day. */
    val days: Map<Long, Int> = emptyMap(),
    val sprint: Sprint? = null,
    val log: List<SprintLog> = emptyList(),
) {
    fun today(day: Long): Int = days[day] ?: 0

    /** Adds a net word change (deleting counts back, never below zero). */
    fun record(day: Long, delta: Int, now: Long): Goals {
        if (delta == 0) return this
        val kept = days.filterKeys { it > day - KEEP_DAYS && it != day }
        val total = maxOf(0, today(day) + delta)
        val running = sprint?.takeIf { now < it.endAt }
        return copy(
            days = if (total == 0) kept else kept + (day to total),
            sprint = running?.copy(words = maxOf(0, running.words + delta)) ?: sprint,
        )
    }

    /** Consecutive days at or above target, ending today (or yesterday while today is open). */
    fun streak(day: Long): Int {
        if (target <= 0) return 0
        var d = if (today(day) >= target) day else day - 1
        var n = 0
        while ((days[d] ?: 0) >= target) {
            n++
            d--
        }
        return n
    }

    fun startSprint(now: Long, minutes: Int): Goals =
        copy(sprint = Sprint(minutes, now + minutes * 60_000L))

    fun sprintRemainingMs(now: Long): Long = sprint?.let { maxOf(0L, it.endAt - now) } ?: 0L

    /** Logs the sprint once its time is up; no change before that. */
    fun finishSprint(now: Long): Goals {
        val s = sprint ?: return this
        if (now < s.endAt) return this
        return copy(sprint = null, log = (listOf(SprintLog(s.minutes, s.words, s.endAt)) + log).take(LOG_SIZE))
    }

    fun cancelSprint(): Goals = if (sprint == null) this else copy(sprint = null)

    companion object {
        val TARGETS = listOf(0, 250, 500, 1000, 2000)
        val SPRINTS = listOf(5, 10, 15, 25)
        const val KEEP_DAYS = 400
        const val LOG_SIZE = 20
    }
}

data class PageState(val settings: Settings = Settings(), val goals: Goals = Goals())
