package dev.field.reel

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * On-device speech-to-text: Silero VAD cuts speech into segments, Parakeet
 * TDT 0.6B v3 transcribes each one (language detected automatically).
 *
 * Everything runs on one background thread, in submission order, so live
 * audio and file jobs never overlap. The ~1 GB model loads on first use and
 * is released after 90 s idle, so REEL doesn't hold the memory in the
 * background.
 */
object Transcriber {

    private const val WINDOW = 512 // Silero VAD window at 16 kHz
    private const val IDLE_RELEASE_S = 90L

    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "reel-asr").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private lateinit var app: Context
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var releaseTask: ScheduledFuture<*>? = null

    // Live session state (worker thread only).
    private var liveTake = 0
    private var liveDecimator = Audio.Decimator()
    private var pending = FloatArray(0)

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ---- live: called by RecorderService ----

    fun beginLive(takeId: Int) = worker.execute {
        if (!ensureEngine(takeId)) return@execute
        vad?.reset()
        pending = FloatArray(0)
        liveDecimator = Audio.Decimator()
        liveTake = takeId
        ReelStore.asr.update { AsrState(busyTakeId = takeId) }
    }

    /** Copies the chunk: the recorder reuses its buffer. */
    fun feedLive(pcm: ShortArray, length: Int) {
        val chunk = pcm.copyOf(length)
        worker.execute {
            val take = liveTake
            if (take == 0) return@execute
            pushToVad(liveDecimator.process(chunk, chunk.size), take)
        }
    }

    fun endLive() = worker.execute {
        val take = liveTake
        if (take != 0) {
            vad?.flush()
            drainSegments(take)
        }
        liveTake = 0
        ReelStore.asr.update { AsrState() }
        scheduleRelease()
    }

    // ---- files: transcribe (or re-transcribe) an existing take ----

    fun transcribeTake(take: Take) = worker.execute {
        if (!ensureEngine(take.id)) return@execute
        try {
            vad?.reset()
            pending = FloatArray(0)
            ReelStore.setTranscript(take.id, "")
            ReelStore.asr.update { AsrState(busyTakeId = take.id, progress = 0f) }
            val decimator = Audio.Decimator()
            M4aDecoder.decode(ReelStore.file(take)) { pcm, rate, progress ->
                val samples = if (rate == Audio.RECORD_RATE) decimator.process(pcm, pcm.size) else Audio.resampleToAsr(pcm, rate)
                pushToVad(samples, take.id)
                ReelStore.asr.update { it.copy(progress = progress) }
            }
            vad?.flush()
            drainSegments(take.id)
            ReelStore.asr.update { AsrState() }
        } catch (e: Exception) {
            ReelStore.asr.update { AsrState(error = "TRANSCRIBE FAILED / " + (e.message ?: "").uppercase()) }
        }
        scheduleRelease()
    }

    // ---- engine ----

    private fun pushToVad(samples: FloatArray, takeId: Int) {
        val v = vad ?: return
        val buf = if (pending.isEmpty()) samples else pending + samples
        var offset = 0
        while (buf.size - offset >= WINDOW) {
            v.acceptWaveform(buf.copyOfRange(offset, offset + WINDOW))
            offset += WINDOW
        }
        pending = buf.copyOfRange(offset, buf.size)
        drainSegments(takeId)
    }

    private fun drainSegments(takeId: Int) {
        val v = vad ?: return
        val r = recognizer ?: return
        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            val stream = r.createStream()
            try {
                stream.acceptWaveform(segment.samples, Audio.ASR_RATE)
                r.decode(stream)
                ReelStore.appendTranscript(takeId, r.getResult(stream).text)
                ReelStore.asr.update { it.copy(segments = it.segments + 1) }
            } finally {
                stream.release()
            }
        }
    }

    /** Loads Parakeet + VAD if needed. False (with an error shown) if it can't. */
    private fun ensureEngine(takeId: Int): Boolean {
        releaseTask?.cancel(false)
        releaseTask = null
        if (recognizer != null && vad != null) return true
        if (!ParakeetModel.isReady(app)) {
            ReelStore.asr.update { AsrState(error = "MODEL NOT INSTALLED / DOWNLOAD IT IN CONTROL") }
            return false
        }
        ReelStore.asr.update { AsrState(busyTakeId = takeId, loading = true) }
        return try {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    // Feature size is read from the model's own metadata (128 for v3).
                    featConfig = FeatureConfig(sampleRate = Audio.ASR_RATE, featureDim = 128),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = ParakeetModel.encoder(app),
                            decoder = ParakeetModel.decoder(app),
                            joiner = ParakeetModel.joiner(app),
                        ),
                        tokens = ParakeetModel.tokens(app),
                        numThreads = threads,
                        modelType = "nemo_transducer",
                    ),
                    decodingMethod = "greedy_search",
                ),
            )
            vad = Vad(
                assetManager = app.assets,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = "silero_vad.onnx",
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        windowSize = WINDOW,
                        maxSpeechDuration = 15f, // longer context = better accuracy
                    ),
                    sampleRate = Audio.ASR_RATE,
                    numThreads = 1,
                ),
            )
            ReelStore.asr.update { it.copy(loading = false) }
            true
        } catch (e: Throwable) { // includes UnsatisfiedLinkError from the native library
            release()
            ReelStore.asr.update { AsrState(error = "ENGINE FAILED / " + (e.message ?: e.javaClass.simpleName).uppercase()) }
            false
        }
    }

    private fun scheduleRelease() {
        releaseTask?.cancel(false)
        releaseTask = worker.schedule({ if (liveTake == 0) release() }, IDLE_RELEASE_S, TimeUnit.SECONDS)
    }

    private fun release() {
        try { recognizer?.release() } catch (_: Throwable) {}
        try { vad?.release() } catch (_: Throwable) {}
        recognizer = null
        vad = null
    }
}
