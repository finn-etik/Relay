package dev.field.reel

/**
 * Small pure audio helpers (unit-tested).
 *
 * The microphone is captured once at 48 kHz for the recording; speech
 * recognition (VAD + Parakeet) wants 16 kHz float samples.
 */
object Audio {
    const val RECORD_RATE = 48_000
    const val ASR_RATE = 16_000

    /**
     * 48 kHz 16-bit PCM -> 16 kHz floats in [-1, 1], averaging each group of
     * three samples (a simple low-pass before decimation; speech energy above
     * 8 kHz is negligible for recognition). [carry] keeps the 0-2 leftover
     * samples between calls so chunk boundaries don't matter.
     */
    class Decimator {
        private val carry = ShortArray(2)
        private var carryCount = 0

        fun process(pcm: ShortArray, length: Int): FloatArray {
            val total = carryCount + length
            val out = FloatArray(total / 3)
            var o = 0
            var i = 0 // index into the virtual stream carry ++ pcm
            fun at(k: Int): Int = if (k < carryCount) carry[k].toInt() else pcm[k - carryCount].toInt()
            while (i + 2 < total) {
                out[o++] = (at(i) + at(i + 1) + at(i + 2)) / (3f * 32768f)
                i += 3
            }
            val rest = total - i
            for (k in 0 until rest) carry[k] = at(i + k).toShort()
            carryCount = rest
            return out
        }
    }

    /** Any other rate -> 16 kHz floats by linear interpolation (per chunk; for imported files). */
    fun resampleToAsr(pcm: ShortArray, rate: Int): FloatArray {
        if (rate == ASR_RATE) return FloatArray(pcm.size) { pcm[it] / 32768f }
        val n = (pcm.size.toLong() * ASR_RATE / rate).toInt()
        return FloatArray(n) { i ->
            val pos = i.toDouble() * rate / ASR_RATE
            val k = pos.toInt().coerceAtMost(pcm.size - 1)
            val next = minOf(k + 1, pcm.size - 1)
            val frac = (pos - k).toFloat()
            (pcm[k] * (1 - frac) + pcm[next] * frac) / 32768f
        }
    }

    /** Peak level 0..1 of a PCM chunk, for the input meter. */
    fun peak(pcm: ShortArray, length: Int): Float {
        var max = 0
        for (k in 0 until length) {
            val v = kotlin.math.abs(pcm[k].toInt())
            if (v > max) max = v
        }
        return max / 32768f
    }
}
