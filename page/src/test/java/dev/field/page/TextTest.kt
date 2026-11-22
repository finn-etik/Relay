package dev.field.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextTest {

    @Test fun countsWordsNotMarkup() {
        assertEquals(0, Text.words(""))
        assertEquals(0, Text.words("## -- **"))
        assertEquals(4, Text.words("# The quick-witted fox's den"))
        assertEquals(3, Text.words("one\ntwo  three"))
        assertEquals(2, Text.words("- 2026 words"))
    }

    @Test fun readingTimeRoundsUp() {
        assertEquals(0, Text.readingMinutes(0))
        assertEquals(1, Text.readingMinutes(1))
        assertEquals(1, Text.readingMinutes(230))
        assertEquals(2, Text.readingMinutes(231))
    }

    @Test fun titleIsFirstLineWithoutMarkers() {
        assertEquals("Chapter One", Text.title("\n\n# Chapter One\nIt began."))
        assertEquals("a quote", Text.title("> a quote"))
        assertEquals("", Text.title("  \n\n"))
        assertEquals("Chapter One", Text.fileName("Chapter: One!/"))
        assertEquals("untitled", Text.fileName(""))
    }

    @Test fun onlyPlaceholderNamesFollowTheTitle() {
        for (n in listOf("untitled.md", "Untitled.txt", "untitled-2.md", "untitled (3).md", "untitled")) {
            assertEquals(n, true, Text.isPlaceholderName(n))
        }
        for (n in listOf("Chapter One.md", "untitled draft.md", "notes.txt")) {
            assertEquals(n, false, Text.isPlaceholderName(n))
        }
    }

    @Test fun paragraphIsTheCursorLine() {
        val t = "first\nsecond line\nthird"
        assertEquals(Span(0, 5), Text.paragraph(t, 0))
        assertEquals(Span(0, 5), Text.paragraph(t, 5))
        assertEquals(Span(6, 17), Text.paragraph(t, 6))
        assertEquals(Span(18, 23), Text.paragraph(t, 23))
        assertEquals(Span(0, 0), Text.paragraph("", 0))
    }

    @Test fun sentenceFollowsTheCursor() {
        val t = "One fish. Two fish! Red?"
        assertEquals(Span(0, 9), Text.sentence(t, 3))
        assertEquals(Span(0, 9), Text.sentence(t, 9))   // just typed the full stop
        assertEquals(Span(10, 19), Text.sentence(t, 10)) // first letter of the next one
        assertEquals(Span(20, 24), Text.sentence(t, 24))
    }

    @Test fun sentenceKeepsDecimalsAndStaysInItsParagraph() {
        val t = "Pi is 3.14 today.\nNext line"
        assertEquals(Span(0, 17), Text.sentence(t, 8))
        assertEquals(Span(18, 27), Text.sentence(t, 20))
        assertEquals(listOf(Span(0, 17), Span(18, 27)), Text.sentences(t))
    }

    @Test fun sentenceIncludesClosingQuote() {
        val t = "She said \"go.\" He went."
        assertEquals(Span(0, 14), Text.sentence(t, 2))
        assertEquals(Span(15, 23), Text.sentence(t, 18))
    }

    @Test fun wrapAndUnwrap() {
        val bold = Text.wrap("a word here", 2, 6, "**")
        assertEquals(Edit("a **word** here", 4, 8), bold)
        assertEquals(Edit("a word here", 2, 6), Text.wrap(bold.text, bold.selStart, bold.selEnd, "**"))
        assertEquals(Edit("ab**", 3, 3), Text.wrap("ab", 2, 2, "*")) // cursor between the marks
    }

    @Test fun prefixTogglesOnTheCursorLine() {
        assertEquals(Edit("a\n- b", 5, 5), Text.togglePrefix("a\nb", 3, "- "))
        assertEquals(Edit("a\nb", 3, 3), Text.togglePrefix("a\n- b", 5, "- "))
    }

    @Test fun headingCycles() {
        var e = Text.cycleHeading("title", 5)
        assertEquals(Edit("# title", 7, 7), e)
        e = Text.cycleHeading(e.text, e.selStart)
        assertEquals(Edit("## title", 8, 8), e)
        e = Text.cycleHeading(e.text, e.selStart)
        e = Text.cycleHeading(e.text, e.selStart)
        assertEquals(Edit("title", 5, 5), e)
    }

    @Test fun enterContinuesLists() {
        assertEquals(Edit("- milk\n- ", 9, 9), Text.autoList("- milk", "- milk\n", 7))
        assertEquals(Edit("9. nine\n10. ", 12, 12), Text.autoList("9. nine", "9. nine\n", 8))
        assertEquals(Edit("- [x] done\n- [ ] ", 17, 17), Text.autoList("- [x] done", "- [x] done\n", 11))
        assertEquals(Edit("> said\n> ", 9, 9), Text.autoList("> said", "> said\n", 7))
        // mid-document
        assertEquals(Edit("a\n  * b\n  * \nc", 12, 12), Text.autoList("a\n  * b\nc", "a\n  * b\n\nc", 8))
    }

    @Test fun enterOnEmptyItemEndsTheList() {
        assertEquals(Edit("- milk\n", 7, 7), Text.autoList("- milk\n- ", "- milk\n- \n", 10))
    }

    @Test fun enterElsewhereIsLeftAlone() {
        assertNull(Text.autoList("plain", "plain\n", 6))
        assertNull(Text.autoList("- a", "- ab", 4))          // not an Enter
        assertNull(Text.autoList("- a", "- a\n\n", 5))       // more than one char
        assertNull(Text.autoList("- - -", "- - -\n", 6))     // a rule, not a list
        assertNull(Text.autoList("x", "\nx", 1))
    }
}
