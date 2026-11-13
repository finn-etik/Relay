package dev.relay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Drives the watchdog against a fake speaker whose state each test sets. */
class ReconnectWatchdogTest {

    private val token = "tok123"
    private val speaker = SonosSpeaker("Kitchen", "127.0.0.1", "RINCON_K")
    private val actions = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var currentUri = ""
    @Volatile private var transport = "STOPPED"

    private val fake = FakeSonos { rawAction, _ ->
        val action = rawAction.substringAfter('#').trim('"')
        actions += action
        200 to when (action) {
            "GetMediaInfo" -> "<CurrentURI>${SonosControl.xmlEscape(currentUri)}</CurrentURI>"
            "GetTransportInfo" -> "<CurrentTransportState>$transport</CurrentTransportState>"
            else -> "<ok/>"
        }
    }

    // Nobody ever connects to this server, so the watchdog always sees silence.
    private val server = StreamServer(ServerSocket(0).use { it.localPort }, token).also { it.start() }

    init {
        SonosControl.port = fake.port
        SonosControl.lanConnector = null
    }

    @After
    fun tearDown() {
        server.stop()
        fake.close()
        SonosControl.port = 1400
    }

    private fun watchdog(onTakenOver: (SonosSpeaker) -> Unit = {}) = ReconnectWatchdog(
        server = server,
        speaker = { speaker },
        streamUrl = "http://10.0.0.2:8079/stream.aac?t=$token",
        token = token,
        title = "Relay",
        onTakenOver = onTakenOver,
        checkEveryMs = 20,
        graceMs = 50
    )

    private fun runFor(ms: Long, dog: ReconnectWatchdog) {
        dog.start()
        Thread.sleep(ms)
        dog.stop()
    }

    @Test
    fun replaysWhenSpeakerDroppedOurStream() {
        currentUri = "x-rincon-mp3radio://10.0.0.2:8079/stream.aac?t=$token"
        transport = "STOPPED"
        runFor(400, watchdog())
        assertTrue(actions.toString(), actions.contains("Play"))
    }

    @Test
    fun replaysAfterSpeakerRebootForgotTheUri() {
        currentUri = ""
        runFor(400, watchdog())
        assertTrue(actions.toString(), actions.contains("SetAVTransportURI"))
    }

    @Test
    fun leavesAPausedSpeakerAlone() {
        currentUri = "x-rincon-mp3radio://10.0.0.2:8079/stream.aac?t=$token"
        transport = "PAUSED_PLAYBACK"
        runFor(400, watchdog())
        assertTrue(actions.contains("GetTransportInfo"))
        assertTrue(actions.toString(), !actions.contains("Play"))
    }

    @Test
    fun handsOverWhenAnotherSourceIsPlaying() {
        currentUri = "x-sonos-spotify:spotify%3atrack%3a123"
        val latch = CountDownLatch(1)
        var handedOver: SonosSpeaker? = null
        val dog = watchdog { handedOver = it; latch.countDown() }
        dog.start()
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        dog.stop()
        assertEquals(speaker, handedOver)
        assertTrue(actions.toString(), !actions.contains("Play") && !actions.contains("SetAVTransportURI"))
    }
}
