package dev.field.lore

import com.github.luben.zstd.ZstdInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks against the real world, which unit tests cannot stand in for:
 * that the catalogue's download addresses exist, and that the reader copes
 * with an archive Kiwix actually built. They need the network, so they run
 * only when `LORE_LIVE=1` (CI has a step for it) and write what they found
 * to `build/lore-live.txt`.
 */
class LiveTest {
    private val live = System.getenv("LORE_LIVE") == "1"
    private val report = File("build/lore-live.txt")

    private fun note(line: String) {
        report.parentFile?.mkdirs()
        report.appendText(line + "\n")
    }

    private fun open(url: String, range: String? = null): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "LORE (Field kit) test")
        if (range != null) c.setRequestProperty("Range", range)
        return c
    }

    @Test fun everyModelCanBeDownloadedWithoutAnAccount() {
        assumeTrue(live)
        for (model in Catalog.MODELS) {
            val c = open(model.url, "bytes=0-0")
            val code = c.responseCode
            val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: c.contentLengthLong
            c.disconnect()
            note("model ${model.id}: HTTP $code, ${Format.bytes(total)} ($total bytes) ${model.url}")
            assertTrue("${model.id}: HTTP $code", code == 200 || code == 206)
            assertTrue("${model.id}: only $total bytes", total > 100L shl 20)
        }
    }

    @Test fun everyPackIsPublished() {
        assumeTrue(live)
        val listings = HashMap<String, String>()
        // For choosing packs: every English Wikipedia edition Kiwix lists, newest of each.
        val all = open(Catalog.KIWIX + "wikipedia/").inputStream.use { String(it.readBytes()) }
        val editions = Regex("href=\"(wikipedia_(?:en_top|en_all|en_100|simple)[a-z0-9_-]*?_)\\d{4}-\\d{2}\\.zim\"").findAll(all).map { it.groupValues[1] }.distinct()
        for (prefix in editions) Packs.latest(all, Pack("", "", "", "wikipedia", prefix))?.let { note("listed ${it.file} ${it.size}") }

        for (pack in Catalog.PACKS) {
            val listing = listings.getOrPut(pack.listing) { open(pack.listing).inputStream.use { String(it.readBytes()) } }
            val remote = Packs.latest(listing, pack)
            note("pack ${pack.id}: ${remote?.file} ${remote?.size} ${remote?.url}")
            assertNotNull("${pack.id}: nothing named ${pack.prefix}* in ${pack.listing}", remote)
            val c = open(remote!!.url, "bytes=0-3")
            val code = c.responseCode
            val magic = c.inputStream.use { it.readBytes() }
            val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
            c.disconnect()
            note("  HTTP $code, ${Format.bytes(total)}, resumable=${code == 206}")
            assertEquals("${pack.id}: not a ZIM file", listOf(0x5A, 0x49, 0x4D, 0x04), magic.take(4).map { it.toInt() and 0xFF })
        }
    }

    /** A whole small archive (Vikidia, a children's encyclopedia built with the same tool as Wikipedia's). */
    @Test fun readsAnArchiveKiwixBuilt() {
        assumeTrue(live)
        val pack = Pack("vikidia", "VIKIDIA", "", "vikidia", "vikidia_en_all_nopic_")
        val remote = Packs.latest(open(pack.listing).inputStream.use { String(it.readBytes()) }, pack)
        assertNotNull("no vikidia archive listed", remote)
        val file = File("build/test.zim")
        file.parentFile?.mkdirs()
        open(remote!!.url).inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        note("archive ${remote.file}: ${file.length()} bytes")

        RandomAccessFile(file, "r").use { raf ->
            val bytes = object : Bytes {
                override val size = raf.length()
                override fun read(position: Long, into: ByteArray, offset: Int, length: Int) {
                    synchronized(raf) { raf.seek(position); raf.readFully(into, offset, length) }
                }
            }
            val zim = Zim(bytes) { ZstdInputStream(it) }
            note("  entries=${zim.entryCount} titles=${zim.titleCount} namespace=${zim.articleNamespace} title=${zim.metadata("Title")} date=${zim.metadata("Date")}")
            assertTrue("no titles", zim.titleCount > 100)

            // Walk the alphabet: every article found by prefix must be found again by its exact title, and read.
            var articles = 0
            var passages = 0
            for (letter in 'A'..'Z') {
                for (entry in zim.titlesFrom(letter.toString(), 8)) {
                    assertEquals(entry.index, zim.byTitle(entry.title)?.index)
                    val target = zim.resolve(entry) ?: continue
                    if (target.mime?.startsWith("text/html") != true) continue
                    val html = String(zim.content(target), Charsets.UTF_8)
                    val found = Passages.of(Html.blocks(html))
                    if (articles < 3) note("  ${entry.title} -> ${target.title}: ${html.length} chars, ${found.size} passages; ${found.firstOrNull()?.text?.take(120)}")
                    articles++
                    passages += found.size
                }
            }
            note("  read $articles articles, $passages passages")
            assertTrue("read only $articles articles", articles > 50)
            assertTrue("found only $passages passages", passages > articles)

            // The whole pipeline, from a question to quoted sources.
            val sources = Ask.fromArticles("What is a volcano?", Ask.articles("What is a volcano?", 3) { title ->
                val e = zim.byTitle(title)?.let(zim::resolve) ?: return@articles null
                if (e.mime?.startsWith("text/html") != true) return@articles null
                Article(e.title, "VIKIDIA", "vikidia\t${e.title}", Passages.of(Html.blocks(String(zim.content(e), Charsets.UTF_8))))
            }, 3000)
            note("  question 'What is a volcano?' -> " + sources.joinToString(" | ") { it.label + ": " + it.text.take(80) })
            assertTrue("no sources for the volcano question", sources.isNotEmpty())
        }
    }
}
