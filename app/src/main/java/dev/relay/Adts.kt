package dev.relay

/** ADTS framing for raw AAC-LC access units (7-byte header, no CRC). */
object Adts {

    private const val PROFILE_LC = 2

    fun header(frameLen: Int, sampleRate: Int, channels: Int): ByteArray {
        val len = frameLen + 7
        val freq = freqIndex(sampleRate)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // sync, MPEG-4, layer 0, no CRC
            (((PROFILE_LC - 1) shl 6) or (freq shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or ((len shr 11) and 3)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte()
        )
    }

    fun freqIndex(rate: Int): Int = when (rate) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        7350 -> 12
        else -> throw IllegalArgumentException("Unsupported AAC sample rate $rate")
    }
}
