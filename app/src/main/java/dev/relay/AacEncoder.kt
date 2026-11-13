package dev.relay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream

/** Platform AAC-LC encoder producing ADTS-framed output. Not thread-safe. */
class AacEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrate: Int
) {

    private var codec: MediaCodec? = null
    private var ptsUs = 0L
    private val info = MediaCodec.BufferInfo()

    fun start() {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: Exception) {
            encoder.release()
            throw e
        }
        codec = encoder
    }

    /**
     * Feed 16-bit PCM, get back whatever ADTS AAC frames are ready. Output
     * lags input by a frame or two; it is collected on the next call.
     */
    fun encode(pcm: ByteArray, length: Int): ByteArray {
        val encoder = codec ?: return EMPTY
        val out = ByteArrayOutputStream()
        var offset = 0
        var stalls = 0
        while (offset < length) {
            val inIndex = encoder.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val input = encoder.getInputBuffer(inIndex) ?: break
                input.clear()
                val chunk = minOf(length - offset, input.capacity())
                input.put(pcm, offset, chunk)
                encoder.queueInputBuffer(inIndex, 0, chunk, ptsUs, 0)
                ptsUs += chunk * 1_000_000L / (sampleRate * channels * 2)
                offset += chunk
                stalls = 0
            } else if (++stalls > 50) {
                break // Codec wedged for 0.5 s: drop this chunk rather than hang capture.
            }
            drain(out)
        }
        drain(out)
        return if (out.size() == 0) EMPTY else out.toByteArray()
    }

    private fun drain(out: ByteArrayOutputStream) {
        val encoder = codec ?: return
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, 0)
            if (index < 0) {
                if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
                continue // Format/buffer change notices: keep draining.
            }
            val buffer = encoder.getOutputBuffer(index)
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buffer != null && info.size > 0 && !isConfig) {
                val frame = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(frame)
                out.write(Adts.header(info.size, sampleRate, channels))
                out.write(frame)
            }
            encoder.releaseOutputBuffer(index, false)
        }
    }

    fun stop() {
        val c = codec ?: return
        codec = null
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
