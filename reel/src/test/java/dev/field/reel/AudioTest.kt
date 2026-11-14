package dev.field.reel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTest {

    @Test fun decimatesThreeToOneAndScales() {
        val d = Audio.Decimator()
        val out = d.process(shortArrayOf(3000, 3000, 3000, -32768, -32768, -32768), 6)
        assertArrayEquals(floatArrayOf(3000 / 32768f, -1f), out, 1e-6f)
    }

    @Test fun chunkBoundariesDontMatter() {
        val pcm = ShortArray(3001) { (it * 7 % 2000 - 1000).toShort() }
        val whole = Audio.Decimator().process(pcm, pcm.size)
        val d = Audio.Decimator()
        val parts = ArrayList<Float>()
        var pos = 0
        for (size in listOf(1, 2, 4, 997, 1000, 997)) {
            val chunk = pcm.copyOfRange(pos, pos + size)
            parts.addAll(d.process(chunk, chunk.size).toList())
            pos += size
        }
        assertEquals(whole.size, parts.size)
        assertArrayEquals(whole, parts.toFloatArray(), 0f)
    }

    @Test fun peakIsNormalized() {
        assertEquals(0.5f, Audio.peak(shortArrayOf(100, -16384, 200), 3), 1e-6f)
    }
}
