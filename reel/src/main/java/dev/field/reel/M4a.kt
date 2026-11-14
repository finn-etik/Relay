package dev.field.reel

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * Writes 48 kHz mono 16-bit PCM to an AAC-LC .m4a file (96 kbit/s, the
 * original REEL quality). Timestamps come from the sample count, so a
 * paused take simply continues where it stopped. Not thread-safe.
 */
class M4aWriter(file: File) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxing = false
    private var samplesIn = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Audio.RECORD_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    val durationMs: Long get() = samplesIn * 1000 / Audio.RECORD_RATE

    fun write(pcm: ShortArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(false)
                continue
            }
            val buf = codec.getInputBuffer(index)!!
            buf.clear()
            val n = minOf(length - offset, buf.capacity() / 2)
            buf.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, offset, n)
            codec.queueInputBuffer(index, 0, n * 2, samplesIn * 1_000_000L / Audio.RECORD_RATE, 0)
            samplesIn += n
            offset += n
            drain(false)
        }
    }

    /** Flushes the encoder and closes the file. Safe to call once. */
    fun finish() {
        try {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, samplesIn * 1_000_000L / Audio.RECORD_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            try { if (muxing) muxer.stop() } catch (_: Exception) {}
            muxer.release()
        }
    }

    private fun drain(untilEos: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos || ++waits > 300) return // give up after 3 s
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (muxing && !config && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}

/**
 * Decodes an audio file to 16-bit PCM chunks, downmixed to mono, calling
 * [onChunk] with (samples, sampleRate, progress 0..1). Used to transcribe
 * takes recorded earlier.
 */
object M4aDecoder {

    fun decode(file: File, onChunk: (ShortArray, Int, Float) -> Unit) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); throw IllegalStateException("no audio track") }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputDone = false
        try {
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(inIndex)!!, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (outIndex >= 0) {
                    val buf = codec.getOutputBuffer(outIndex)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    if (frames > 0) {
                        val mono = ShortArray(frames)
                        for (f in 0 until frames) {
                            var sum = 0
                            for (c in 0 until channels) sum += shorts.get(f * channels + c)
                            mono[f] = (sum / channels).toShort()
                        }
                        val progress = if (durationUs > 0) (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f) else 0f
                        onChunk(mono, rate, progress)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            extractor.release()
        }
    }
}
