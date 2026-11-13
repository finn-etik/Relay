package dev.relay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Session(val speaker: SonosSpeaker, val startedAt: Long)

/**
 * Process-wide streaming state. The UI and the tile observe flows instead
 * of polling, so nothing runs while nothing changes.
 */
object RelayState {

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Sonos volume 0-100 as last reported by the speaker; -1 = unknown. */
    val volume = MutableStateFlow(-1)

    /** One-line status shown in the UI (errors, takeovers). Null = none. */
    val message = MutableStateFlow<String?>(null)

    /** Bytes of AAC sent this session. Single writer: the capture thread. */
    @Volatile var txBytes = 0L

    val isActive: Boolean get() = _session.value != null

    fun setSession(session: Session?) {
        _session.value = session
    }

    fun say(text: String?) {
        message.value = text
    }
}
