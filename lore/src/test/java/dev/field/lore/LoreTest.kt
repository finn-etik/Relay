package dev.field.lore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoreTest {
    private val wiki = Catalog.pack("wikipedia")!!

    @Test fun picksTheNewestArchiveFromAListing() {
        val apache = """
            <a href="wikipedia_en_all_maxi_2025-08.zim">wikipedia_en_all_maxi_2025-08.zim</a>          2025-08-20 03:11  111G
            <a href="wikipedia_en_all_nopic_2024-06.zim">wikipedia_en_all_nopic_2024-06.zim</a>         2024-06-28 21:09   54G
            <a href="wikipedia_en_all_nopic_2025-08.zim">wikipedia_en_all_nopic_2025-08.zim</a>         2025-08-15 10:12   43G
            <a href="wikipedia_en_all_nopic_2025-08.zim.meta4">x</a>
        """.trimIndent()
        assertEquals(
            Remote("wikipedia_en_all_nopic_2025-08.zim", "https://download.kiwix.org/zim/wikipedia/wikipedia_en_all_nopic_2025-08.zim", "43G"),
            Packs.latest(apache, wiki),
        )
        val table = "<tr><td><a href=\"wikibooks_en_all_nopic_2026-01.zim\">wikibooks…</a></td><td>2026-Jan-16 17:30</td><td>2.9 GiB</td></tr>"
        assertEquals("2.9GiB", Packs.latest(table, Catalog.pack("wikibooks")!!)!!.size)
        val nginx = "<a href=\"wikipedia_en_all_mini_2026-06.zim\">wikipedia_en_all_mini_2026-06.zim</a>   12-Jun-2026 09:30   11811160064"
        assertEquals("", Packs.latest(nginx, Catalog.pack("wikipedia-mini")!!)!!.size)
        assertNull(Packs.latest("<html>nothing here</html>", wiki))
    }

    @Test fun catalogIsConsistent() {
        assertEquals(Catalog.MODELS.size, Catalog.MODELS.map { it.id }.toSet().size)
        assertEquals(Catalog.PACKS.size, Catalog.PACKS.map { it.id }.toSet().size)
        assertTrue(Catalog.MODELS.all { it.url.startsWith("https://") && it.file.endsWith(".litertlm") })
        assertEquals("gemma-4-e2b", Catalog.model("nonsense").id)
    }

    @Test fun askingStartsAChatAndMovesItToTheFront() {
        var chats = Chats.ask(emptyList(), 1, 10, " First question ", 100)
        chats = Chats.ask(chats, 2, 20, "Other", 200)
        chats = Chats.ask(chats, 1, 11, "Follow-up", 300)
        assertEquals(listOf(1L, 2L), chats.map { it.id })
        assertEquals("First question", chats[0].title)
        assertEquals(listOf("First question", "Follow-up"), chats[0].turns.map { it.question })
        assertEquals(300, chats[0].updated)
    }

    @Test fun turnsUpdateSettleAndDrop() {
        var chats = Chats.ask(emptyList(), 1, 10, "Q", 100)
        chats = Chats.update(chats, 1, 10) { it.copy(answer = "A", stage = Stage.WRITING) }
        assertEquals("A", chats[0].turns[0].answer)
        assertEquals(Stage.STOPPED, Chats.settle(chats)[0].turns[0].stage)
        chats = Chats.update(chats, 1, 10) { it.copy(stage = Stage.DONE) }
        assertTrue(Chats.settle(chats) == chats)
        chats = Chats.ask(chats, 1, 11, "Q2", 200)
        assertEquals(listOf("Q" to "A"), Chats.before(chats[0], 11))
        assertEquals(1, Chats.dropLast(chats, 1)[0].turns.size)
        assertTrue(Chats.dropLast(Chats.dropLast(chats, 1), 1).isEmpty())
        assertTrue(Chats.remove(chats, 1).isEmpty())
    }

    @Test fun onlyTheNewestChatsAreKept() {
        var chats = emptyList<Chat>()
        for (i in 1..(Chats.KEEP + 5)) chats = Chats.ask(chats, i.toLong(), i.toLong(), "q$i", i.toLong())
        assertEquals(Chats.KEEP, chats.size)
        assertEquals((Chats.KEEP + 5).toLong(), chats.first().id)
    }

    @Test fun formatsSizes() {
        assertEquals("2.6 GB", Format.bytes(2_588_147_712))
        assertEquals("49.0 GB", Format.bytes(49_000_000_000))
        assertEquals("347 MB", Format.bytes(347_251_840))
        assertEquals("12 KB", Format.bytes(12_400))
        assertEquals("52.7 GB", Remote("f", "u", "49G", 52_700_000_000).sizeLabel)
        assertEquals("49G", Remote("f", "u", "49G").sizeLabel)
        assertEquals("37%", Format.percent(37, 100))
        assertEquals("…", Format.percent(5, 0))
        assertEquals("6 912 345", Format.count(6912345))
    }
}
