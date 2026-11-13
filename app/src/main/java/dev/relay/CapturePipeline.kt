package dev.relay

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import kotlin.concurrent.thread

/**
 * Captured playback audio -> AAC -> [StreamServer].
 *
 * One dedicated thread reads PCM from Android's playback-capture API,
 * encodes it, and hands the result to the server. It never touches the
 * network itself, so a slow speaker can't stall capture.
 */
class CapturePipeline(
    private val projection: MediaProjection,
    private val server: StreamServer,
    /** Called on the capture thread if capture dies unexpectedly. */
    private val onFailure: () -> Unit
) {

    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var encoder: AacEncoder? = null
    private var worker: Thread? = null

    /**
     * Starts encoding and capture. Throws if either can't start; the caller
     * must call [stop] to clean up afterwards either way.
     * Requires the RECORD_AUDIO permission (the UI asks for it first).
     */
    fun start() {
        val aac = AacEncoder(SAMPLE_RATE, CHANNELS, BIT_RATE)
        encoder = aac
        aac.start()

        val rec = buildRecorder()
        recorder = rec
        rec.startRecording()
        check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "capture refused" }

        running = true
        worker = thread(name = "relay-capture") { captureLoop(rec, aac) }
    }

    /** Idempotent. Waits for the capture thread so the codec is never freed mid-encode. */
    fun stop() {
        running = false
        recorder?.let { try { it.stop() } catch (_: Exception) {} } // unblocks read()
        worker?.let { try { it.join(1500) } catch (_: InterruptedException) {} }
        worker = null
        recorder?.let { try { it.release() } catch (_: Exception) {} }
        recorder = null
        encoder?.stop()
        encoder = null
    }

    private fun captureLoop(rec: AudioRecord, aac: AacEncoder) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val pcm = ByteArray(PCM_CHUNK_BYTES)
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n < 0) break // recorder stopped or died
                // Only spend CPU on encoding while a speaker is listening.
                if (n == 0 || !server.hasClients()) continue

                val aacBytes = aac.encode(pcm, n)
                if (aacBytes.isNotEmpty()) {
                    server.broadcast(aacBytes)
                    RelayState.txBytes += aacBytes.size
                }
            }
        } catch (_: Throwable) {
            // Recorder or codec failed; reported below.
        }
        if (running) onFailure()
    }

    @SuppressLint("MissingPermission")
    private fun buildRecorder(): AudioRecord {
        val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer * 4, PCM_CHUNK_BYTES * 8))
            .setAudioPlaybackCaptureConfig(capture)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("capture unavailable")
        }
        return rec
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 2

        /** 256 kbit/s AAC-LC. Raise to 320_000 for more headroom. */
        const val BIT_RATE = 256_000

        /** ~46 ms of 16-bit stereo audio per read. */
        private const val PCM_CHUNK_BYTES = 8192
    }
}
