package dev.relay

import kotlin.concurrent.thread

/**
 * Keeps the speaker connected to the stream, without fighting the user.
 *
 * Every 5 s it checks whether any speaker is pulling the stream. After
 * 12 s with nobody listening, it asks the speaker what it is doing:
 *
 * - unreachable (rebooting, asleep): wait and check again;
 * - paused by the user: leave it alone;
 * - holding our stream, or nothing at all (e.g. after a reboot): re-issue Play;
 * - playing some other source: the user took the speaker over in the
 *   Sonos app, so report [onTakenOver] instead of grabbing it back.
 *
 * Pure JVM (no Android), so it is unit-testable.
 */
class ReconnectWatchdog(
    private val server: StreamServer,
    private val speaker: () -> SonosSpeaker?,
    private val streamUrl: String,
    private val token: String,
    private val title: String,
    /** Called on the watchdog thread every cycle (used to refresh the wake lock). */
    private val onTick: () -> Unit = {},
    /** Called on the watchdog thread; the watchdog stops afterwards. */
    private val onTakenOver: (SonosSpeaker) -> Unit,
    private val checkEveryMs: Long = 5_000,
    private val graceMs: Long = 12_000
) {

    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        running = true
        worker = thread(name = "relay-watchdog", isDaemon = true) {
            try {
                loop()
            } catch (_: InterruptedException) {
                // Stopped.
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun loop() {
        var silentSince = 0L
        while (running) {
            Thread.sleep(checkEveryMs)
            onTick()
            if (server.hasClients()) {
                silentSince = 0L
                continue
            }
            val now = System.currentTimeMillis()
            if (silentSince == 0L) silentSince = now
            if (now - silentSince < graceMs) continue
            silentSince = now // next check after another grace period

            val target = speaker() ?: return
            val playing = SonosControl.currentUri(target.ip) ?: continue // unreachable
            if (!running || speaker() != target) continue // stopped or switched meanwhile

            if (playing.isNotEmpty() && !playing.contains(token)) {
                onTakenOver(target)
                return
            }
            if (SonosControl.transportState(target.ip) != "PAUSED_PLAYBACK") {
                SonosControl.playStreamBlocking(target.ip, streamUrl, title)
            }
        }
    }
}
