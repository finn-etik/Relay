package dev.field.lore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun tokensAreLowerCaseWordsWithPluralsFolded() {
        assertEquals(listOf("marie", "curie", "won", "nobel", "prize", "in", "1903"), Words.tokens("Marie Curie's won Nobel Prizes in 1903!"))
        assertEquals("story", Words.stem("stories"))
        assertEquals("class", Words.stem("classes"))
        assertEquals("virus", Words.stem("virus"))
        assertEquals("glass", Words.stem("glass"))
    }

    @Test fun termsDropFillerAndRepeats() {
        assertEquals(listOf("capital", "sweden"), Words.terms("What is the capital of Sweden, the capital?"))
    }

    private val article = """
        <html><head><title>Test</title><style>p{color:red}</style></head><body>
        <h1>Marie Curie</h1>
        <div class="hatnote">For other uses, see Curie.</div>
        <table class="infobox"><tr><td>Born <table><tr><td>1867</td></tr></table></td></tr></table>
        <p><b>Marie Curie</b> was a physicist&nbsp;&amp; chemist<sup class="reference"><a href="#c1">[1]</a></sup>.</p>
        <!-- a comment <p>not text</p> -->
        <details><summary><h2 id="Life">Early life<span class="mw-editsection">[edit]</span></h2></summary>
        <p>She was born in Warsaw.<br>Her mass&#8211;energy work: <math alttext="{\displaystyle E=mc^{2}}"><semantics>junk</semantics></math></p>
        <ul><li>First item</li><li>Second &lt;item&gt;</li></ul>
        </details>
        <h2>References</h2><ol class="references"><li>Some citation</li></ol><p>Stray reference text.</p>
        <h2>Legacy</h2><p>Remembered widely.</p>
        <script>var x = "<p>no</p>";</script>
        </body></html>
    """.trimIndent()

    @Test fun htmlBecomesTextWithoutFurniture() {
        val blocks = Html.blocks(article)
        assertEquals(
            listOf(
                "H:Marie Curie",
                "P:Marie Curie was a physicist & chemist.",
                "H:Early life",
                "P:She was born in Warsaw.",
                "P:Her mass–energy work: E=mc^{2}",
                "P:First item",
                "P:Second <item>",
                "H:References",
                "P:Stray reference text.",
                "H:Legacy",
                "P:Remembered widely.",
            ),
            blocks.map { (if (it.heading) "H:" else "P:") + it.text },
        )
    }

    @Test fun passagesFollowHeadingsAndSkipReferenceSections() {
        val passages = Passages.of(Html.blocks(article))
        assertEquals(listOf("Marie Curie", "Early life", "Legacy"), passages.map { it.heading })
        assertTrue(passages[1].text.contains("Warsaw") && passages[1].text.contains("Second <item>"))
        assertFalse(passages.any { it.text.contains("Stray") })
    }

    @Test fun longParagraphsAreCutAtSentences() {
        val text = (1..40).joinToString(" ") { "Sentence number $it is here." }
        val parts = Passages.split(text, 200)
        assertTrue(parts.size > 4)
        assertTrue(parts.all { it.length <= 200 })
        assertTrue(parts.dropLast(1).all { it.endsWith(".") })
        assertEquals(text, parts.joinToString(" "))
        assertEquals(listOf("x".repeat(250).take(100), "x".repeat(100), "x".repeat(50)), Passages.split("x".repeat(250), 100))
    }

    @Test fun notesAreCutByHeadingsAndBlankLines() {
        val note = "# Trip\n\nPack the tent\nand the stove.\n\n## Food\n- rice\n- beans\n\n## Notes\nKeep this section."
        val p = Passages.ofNote(note)
        assertEquals(listOf("Trip", "Food", "Notes"), p.map { it.heading })
        assertEquals("Pack the tent and the stove.", p[0].text)
        assertEquals("- rice - beans", p[1].text)
        assertEquals(1, Passages.ofNote("just one line").size)
        assertEquals(0, Passages.ofNote("  \n\n").size)
    }

    @Test fun entitiesDecode() {
        assertEquals("a < b & c é € 😀 &unknown; & done", Html.decode("a &lt; b &amp; c &#233; &#x20AC; &#128512; &unknown; & done"))
    }

    @Test fun brokenHtmlDoesNotThrow() {
        for (bad in listOf("<p>open", "text < 5 and > 3", "<", "<<<>>>", "<table><p>never closed", "<p class=>x</p>", "<p class=\"x>y</p>")) Html.blocks(bad)
        assertEquals(listOf("text < 5 and > 3"), Html.blocks("text < 5 and > 3").map { it.text })
    }
}
