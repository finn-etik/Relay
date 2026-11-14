package dev.field.gate

/**
 * GATE's whole state, and the pure transitions between states.
 *
 * Time is stored as absolute wall-clock instants (endAt), never as a
 * running counter, so the session stays correct when the app is closed,
 * the screen is off, or the process is killed.
 */
enum class Phase { STANDBY, ON, HOLD, DONE }

data class LogEntry(val minutes: Int, val endedAt: Long)

data class GateState(
    val phase: Phase = Phase.STANDBY,
    val presetMin: Int = 25,
    /** When ON: the instant the session ends. */
    val endAt: Long = 0L,
    /** When HOLD: time left when the clock was stopped. */
    val heldRemainingMs: Long = 0L,
    /** When DONE: when the session completed (the DONE flash lasts 3 s). */
    val doneAt: Long = 0L,
    val beep: Boolean = true,
    val log: List<LogEntry> = emptyList(),
) {
    val presetMs: Long get() = presetMin * 60_000L

    fun remainingMs(now: Long): Long = when (phase) {
        Phase.ON -> (endAt - now).coerceIn(0L, presetMs)
        Phase.HOLD -> heldRemainingMs
        Phase.STANDBY -> presetMs
        Phase.DONE -> 0L
    }

    fun elapsedMs(now: Long): Long = when (phase) {
        Phase.ON, Phase.HOLD -> presetMs - remainingMs(now)
        else -> 0L
    }

    // ---- transitions (each returns the new state; no side effects) ----

    fun start(now: Long): GateState = when (phase) {
        Phase.ON -> this
        Phase.HOLD -> copy(phase = Phase.ON, endAt = now + heldRemainingMs, heldRemainingMs = 0L)
        Phase.STANDBY, Phase.DONE -> copy(phase = Phase.ON, endAt = now + presetMs, doneAt = 0L)
    }

    fun hold(now: Long): GateState =
        if (phase != Phase.ON) this
        else copy(phase = Phase.HOLD, heldRemainingMs = remainingMs(now), endAt = 0L)

    fun stop(): GateState =
        if (phase == Phase.STANDBY) this
        else copy(phase = Phase.STANDBY, endAt = 0L, heldRemainingMs = 0L, doneAt = 0L)

    /** Session ran out. Idempotent: only an ON session whose time is up completes. */
    fun complete(now: Long): GateState {
        if (phase != Phase.ON || now < endAt) return this
        val entry = LogEntry(presetMin, endAt)
        return copy(
            phase = Phase.DONE,
            doneAt = now,
            endAt = 0L,
            log = (listOf(entry) + log).take(MAX_LOG),
        )
    }

    /** DONE flashes for 3 s, then the instrument returns to STANDBY. */
    fun settle(now: Long): GateState =
        if (phase == Phase.DONE && now - doneAt >= DONE_FLASH_MS) copy(phase = Phase.STANDBY, doneAt = 0L) else this

    fun selectPreset(min: Int): GateState = if (phase == Phase.STANDBY) copy(presetMin = min) else this

    companion object {
        const val MAX_LOG = 30
        const val DONE_FLASH_MS = 3_000L
        val PRESETS = listOf(5, 10, 25, 50)
    }
}
