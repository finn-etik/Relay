package dev.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AdtsTest {

    @Test
    fun headerFor44kStereoLc() {
        // 100-byte AU -> frame length 107 (0x6B).
        val expected = byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x0D, 0x7F, 0xFC.toByte()
        )
        assertArrayEquals(expected, Adts.header(100, 44100, 2))
    }

    @Test
    fun largeFrameLengthUsesAll13Bits() {
        val h = Adts.header(6000, 48000, 2)
        val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
        org.junit.Assert.assertEquals(6007, len)
    }
}
