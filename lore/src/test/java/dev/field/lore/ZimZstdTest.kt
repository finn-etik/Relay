package dev.field.lore

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** The reader against real Zstandard streams, as on the device. */
class ZimZstdTest {
    @Test fun readsZstandardClustersThatAreFollowedByMoreData() {
        val long = "<p>" + "Plants turn light into sugar. ".repeat(5000) + "</p>"
        val data = ZimFixture(compress = { Zstd.compress(it) })
            .article("Marie_Curie", "Marie Curie", "<p>Physicist and chemist.</p>")
            .article("Photosynthesis", "Photosynthesis", long)
            .redirect("Curie", "Curie", "Marie_Curie")
            .build()
        val zim = Zim(MemoryBytes(data)) { ZstdInputStream(it) }
        // The compressed cluster is followed directly by the title listing: reading must stop where the cluster ends.
        assertEquals("<p>Physicist and chemist.</p>", String(zim.content(zim.resolve(zim.byTitle("Curie")!!)!!)))
        assertEquals(long, String(zim.content(zim.byTitle("Photosynthesis")!!)))
        assertEquals(3, zim.titleCount)
    }
}
