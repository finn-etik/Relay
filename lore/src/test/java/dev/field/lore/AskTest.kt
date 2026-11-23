package dev.field.lore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskTest {
    @Test fun bm25RanksByRelevance() {
        val docs = listOf("the cat sat on the mat", "dogs chase cats in the park", "a treatise on thermodynamics", "cat cat cat")
        val index = Bm25(docs.map(Words::tokens))
        val hits = index.search(Words.terms("cat park"), 10)
        assertEquals(1, hits.first().doc)
        assertEquals(2, hits.first().matched)
        assertEquals(setOf(0, 1, 3), hits.map { it.doc }.toSet())
        assertTrue(index.search(listOf("zebra"), 5).isEmpty())
        assertTrue(Bm25(emptyList()).search(listOf("cat"), 5).isEmpty())
    }

    @Test fun candidatesAreLongestFirstWithoutFillerEdges() {
        val c = Ask.candidates("When was marie curie born?")
        assertEquals(listOf("marie curie born", "marie curie", "curie born", "marie", "curie", "born"), c.map { it.spellings.first() })
        assertEquals(listOf("marie curie", "Marie curie", "Marie Curie"), c[1].spellings)
        assertTrue(Ask.candidates("what is it").isEmpty())
        assertEquals(listOf("DNA"), Ask.candidates("What is DNA?").map { it.spellings.first() })
        assertEquals(listOf("DNA", "Dna"), Ask.candidates("What is DNA?").single().spellings)
    }

    private fun article(title: String, vararg passages: Pair<String, String>) =
        Article(title, "WIKIPEDIA", "wikipedia\t$title", passages.map { Passage(it.first, it.second) })

    private val curie = article(
        "Marie Curie",
        "Marie Curie" to "Marie Curie was a physicist and chemist who studied radioactivity.",
        "Early life" to "She was born in Warsaw in 1867 and moved to Paris.",
        "Nobel Prizes" to "She won the Nobel Prize in Physics in 1903 and in Chemistry in 1911.",
    )
    private val warsaw = article("Warsaw", "Warsaw" to "Warsaw is the capital of Poland.")

    @Test fun articlesAreFoundOnceAndCoveredWordsAreNotLookedUpAgain() {
        val asked = ArrayList<String>()
        val found = Ask.articles("where was marie curie born, Warsaw?", 3) { title ->
            asked += title
            when (title) { "Marie Curie" -> curie; "Marie" -> article("Marie", "Marie" to "A name."); "Warsaw" -> warsaw; else -> null }
        }
        assertEquals(listOf("Marie Curie", "Warsaw"), found.map { it.title })
        assertFalse("Marie" in asked)
        assertTrue(asked.size <= 60)
    }

    @Test fun articleLookupStopsAtTheLimit() {
        val found = Ask.articles("Alpha Beta Gamma Delta", 2) { t -> if (t.length < 6 && ' ' !in t) article(t, t to "text") else null }
        assertEquals(2, found.size)
    }

    @Test fun quotesTheOpeningThenTheBestPassages() {
        val sources = Ask.fromArticles("Which Nobel prize did Marie Curie win?", listOf(curie, warsaw), 5000)
        assertEquals("Marie Curie", sources[0].label)
        assertEquals("Marie Curie · Nobel Prizes", sources[1].label)
        assertEquals(sources.size, sources.distinct().size)
        assertTrue(sources.all { it.origin == Origin.PACK })
        assertEquals("wikipedia\tMarie Curie", sources[0].ref)
    }

    @Test fun linkListsAreNotQuotedAndQuotesAreFew() {
        val many = article(
            "Volcano",
            "Volcano" to "A volcano is a mountain where lava comes from a magma chamber under the ground.",
            "Related" to "List of volcano names",
            *(1..9).map { "Part $it" to "Volcano fact number $it: a volcano of this kind erupts in its own particular way." }.toTypedArray(),
        )
        val sources = Ask.fromArticles("What is a volcano?", listOf(many), 9000)
        assertEquals(5, sources.size)
        assertEquals("Volcano", sources[0].label)
        assertFalse(sources.any { it.section == "Related" })
    }

    @Test fun sourcesStayWithinTheBudget() {
        val long = article("Long", "Long" to "word ".repeat(400), "More" to "other ".repeat(400))
        val sources = Ask.fromArticles("word other", listOf(long), 900)
        assertTrue(sources.sumOf { it.text.length } <= 900 + 4)
        assertTrue(sources.last().text.endsWith("…"))
        assertTrue(Ask.fromArticles("x", emptyList(), 900).isEmpty())
    }

    private val notes = NotesIndex(
        listOf(
            NoteChunk("camping.md", "uri:1", Passage("Gear", "Tent, stove, sleeping bags and the blue tarp.")),
            NoteChunk("recipes.md", "uri:2", Passage("Bread", "Flour, water, salt, and a sourdough starter.")),
            NoteChunk("camping.md", "uri:1", Passage("Food", "Rice and beans for the stove.")),
        )
    )

    @Test fun notesMatchOnlyWhenRelevant() {
        assertEquals(listOf("Gear"), notes.search("what tent and tarp do I have", 3).map { it.passage.heading })
        assertEquals("Bread", notes.search("sourdough bread recipe", 3).first().passage.heading)
        assertTrue(notes.search("Who was Marie Curie?", 3).isEmpty())
        assertTrue(notes.search("what is it", 3).isEmpty())
        // The file name counts as text of every passage in it.
        assertEquals(2, notes.search("camping", 3).size)
        assertEquals("camping.md · Gear", Ask.fromNotes("tent tarp", notes, 1000).single().label)
    }

    @Test fun questionsAboutTheNotesGetTheTableOfContents() {
        assertTrue(Ask.asksAboutNotes("what are my notes about?"))
        assertTrue(Ask.asksAboutNotes("Did I write anything on bread? Have I written about tents"))
        assertTrue(Ask.asksAboutNotes("summarise the notes"))
        assertFalse(Ask.asksAboutNotes("Who was Marie Curie?"))
        assertFalse(Ask.asksAboutNotes("a notable composer"))

        val sources = Ask.fromNotes("what are my notes about?", notes, 2000)
        assertEquals("Your notes · Overview", sources.single().label)
        assertEquals("The notes folder holds 2 notes:\n- camping.md: Gear; Food\n- recipes.md: Bread", sources.single().text)
        assertEquals(2, notes.files)

        // "notes" is not itself a search word: this is about bread.
        val bread = Ask.fromNotes("what do my notes say about sourdough bread?", notes, 2000)
        assertEquals(listOf("Your notes · Overview", "recipes.md · Bread"), bread.map { it.label })

        assertTrue(NotesIndex(emptyList()).overview(500) == null)
        val many = NotesIndex((1..50).map { NoteChunk("note$it.md", "uri:$it", Passage("", "Text of note number $it.")) })
        val short = many.overview(300)!!.text
        assertTrue(short.length <= 300 + 14 && short.endsWith("(and more)") && short.contains("note1.md: Text of note number 1."))
    }

    @Test fun theInstructionSaysWhenNotesWereSearchedInVain() {
        assertTrue(Ask.system("today", sourced = false, notes = 12).contains("12 notes"))
        assertFalse(Ask.system("today", sourced = false, notes = 0).contains("notes folder"))
        assertTrue(Ask.system("today", sourced = true, notes = 12).contains("(my notes)"))
    }

    @Test fun promptNumbersSourcesFromOne() {
        val p = Ask.prompt(" Who? ", listOf(Source(Origin.NOTE, "a.md", "", "note text", "u"), Source(Origin.PACK, "T", "S", "article text", "r")))
        assertEquals("Sources:\n\n[1] a.md (my notes)\nnote text\n\n[2] T · S (encyclopedia)\narticle text\n\nQuestion: Who?", p)
        assertEquals("Who?", Ask.prompt(" Who? ", emptyList()))
    }

    @Test fun historyKeepsTheLatestThatFit() {
        val turns = listOf("q1" to "a".repeat(50), "q2" to "", "q3" to "b".repeat(50), "q4" to "c".repeat(50))
        assertEquals(listOf("q3", "q4"), Ask.history(turns, 110).map { it.first })
        assertEquals(listOf("q1", "q3", "q4"), Ask.history(turns, 1000).map { it.first })
        assertTrue(Ask.history(turns, 10).isEmpty())
    }

    @Test fun reasoningIsHidden() {
        assertEquals("Paris.", Ask.clean("<think>The user asks…</think>\n\nParis."))
        assertEquals("", Ask.clean("<think>still thinking"))
        assertEquals("A B", Ask.clean("A <think>x</think>B"))
        assertEquals("plain", Ask.clean("  plain "))
    }

    @Test fun citationsOfSourcesThatDoNotExistAreRemoved() {
        assertEquals("Stockholm", Ask.clean("Stockholm [1]", sources = 0))
        assertEquals("No information.", Ask.clean("No information [].", sources = 2))
        assertEquals("Born in Warsaw [1], in 1867 [2].", Ask.clean("Born in Warsaw [1], in 1867 [2] [7].", sources = 2))
        assertEquals("list[0] stays [note]", Ask.clean("list[0] stays [note]", sources = 0))
        assertTrue(Ask.system("today", sourced = true).contains("cite"))
        assertFalse(Ask.system("today", sourced = false).contains("[1]"))
    }

    @Test fun chatTitlesAreShort() {
        assertEquals("Who was Marie Curie?", Ask.chatTitle("  Who was\tMarie Curie?\nSecond line"))
        val t = Ask.chatTitle("word ".repeat(30))
        assertTrue(t.length <= 49 && t.endsWith("…"))
    }

    @Test fun readsTitlesOutOfAModelReply() {
        assertEquals(listOf("Rayleigh scattering", "Sky", "Diffuse sky radiation"), Ask.titlesFrom("Here are the titles:\n1. Rayleigh scattering\n- \"Sky\"\n* **Diffuse sky radiation**\n4) Fourth one\n"))
        assertTrue(Ask.titlesFrom("I am not sure what you mean by that question, could you say more about it please?").isEmpty())
        val found = Ask.articlesNamed(listOf("rayleigh scattering", "Nope", "Sky")) { if (it == "Rayleigh scattering" || it == "Sky") article(it, it to "text") else null }
        assertEquals(listOf("Rayleigh scattering", "Sky"), found.map { it.title })
    }
}
