package dev.field.lore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ZimTest {
    // The tests stand gzip in for Zstandard: the reader only needs "a stream that decompresses".
    private val gunzip = Inflater { GZIPInputStream(it) }
    private fun gzip(data: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(data) } }.toByteArray()

    private fun fixture(newScheme: Boolean = true, compressed: Boolean = false, wide: Boolean = false) =
        ZimFixture(newScheme, if (compressed) ::gzip else null, wide)
            .article("Marie_Curie", "Marie Curie", "<p>Physicist and chemist.</p>")
            .article("Photosynthesis", "Photosynthesis", "<p>Plants turn light into sugar.</p>")
            .article("Zürich", "Zürich", "<p>City in Switzerland.</p>")
            .article("Photon", "Photon", "<p>A quantum of light.</p>")
            .article("plain", "", "<p>Entry without a title of its own.</p>")
            .redirect("Maria_Sklodowska", "Maria Sklodowska", "Marie_Curie")
            .metadata("Title", "Test archive")
            .build()

    private fun zim(data: ByteArray) = Zim(MemoryBytes(data), gunzip)

    private fun text(z: Zim, title: String) = String(z.content(z.resolve(z.byTitle(title)!!)!!))

    @Test fun findsArticlesByTitleInEveryLayout() {
        for (new in listOf(true, false)) for (packed in listOf(false, true)) for (wide in listOf(false, true)) {
            val z = zim(fixture(new, packed, wide))
            assertEquals("<p>Physicist and chemist.</p>", text(z, "Marie Curie"))
            assertEquals("<p>City in Switzerland.</p>", text(z, "Zürich"))
            assertEquals("<p>A quantum of light.</p>", text(z, "Photon"))
            assertEquals("<p>Entry without a title of its own.</p>", text(z, "plain"))
            assertNull(z.byTitle("Marie"))
            assertNull(z.byTitle("marie curie"))
            assertNull(z.byTitle("Zz"))
            assertNull(z.byTitle(""))
        }
    }

    @Test fun followsRedirects() {
        val z = zim(fixture())
        val r = z.byTitle("Maria Sklodowska")!!
        assertTrue(r.isRedirect)
        assertEquals("Marie Curie", z.resolve(r)!!.title)
        assertEquals("text/html", z.resolve(r)!!.mime)
    }

    @Test fun listsTitlesByPrefixInOrder() {
        val z = zim(fixture())
        assertEquals(listOf("Photon", "Photosynthesis"), z.titlesFrom("Photo", 10).map { it.title })
        assertEquals(listOf("Photon"), z.titlesFrom("Photo", 1).map { it.title })
        assertEquals(emptyList<String>(), z.titlesFrom("Quark", 10).map { it.title })
    }

    @Test fun newArchivesListArticlesOnly() {
        val z = zim(fixture(newScheme = true))
        assertEquals(6, z.titleCount) // five articles and a redirect; not the metadata or the listing itself
        assertEquals('C', z.articleNamespace)
        assertEquals('A', zim(fixture(newScheme = false)).articleNamespace)
    }

    @Test fun readsMetadataAndPaths() {
        for (new in listOf(true, false)) {
            val z = zim(fixture(new))
            assertEquals("Test archive", z.metadata("Title"))
            assertNull(z.metadata("Missing"))
            assertNotNull(z.byPath(z.articleNamespace, "Marie_Curie"))
            assertNull(z.byPath(z.articleNamespace, "Marie"))
        }
    }

    @Test fun rejectsWhatIsNotZim() {
        for (bad in listOf(ByteArray(10), ByteArray(200), "PK".toByteArray() + ByteArray(100))) {
            try { zim(bad); fail("accepted") } catch (_: ZimException) {}
        }
    }

    @Test fun aTruncatedFileFailsCleanly() {
        val data = fixture(compressed = true)
        val z = zim(data.copyOf(data.size - 60))
        try { text(z, "Marie Curie"); fail("read past the end") } catch (_: java.io.IOException) {}
    }

    @Test fun contentOverTheLimitIsRefused() {
        val z = zim(fixture())
        val e = z.resolve(z.byTitle("Marie Curie")!!)!!
        try { z.content(e, limit = 5); fail("no limit") } catch (_: ZimException) {}
    }
}
