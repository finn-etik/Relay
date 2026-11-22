package dev.field.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleTest {

    private fun found(text: String, kind: IssueKind) =
        Style.check(text).filter { it.kind == kind }.map { text.substring(it.start, it.end) }

    @Test fun fillersAndPhrases() {
        assertEquals(listOf("just", "really", "Kind of"), found("I just think it is really good. Kind of.", IssueKind.FILLER))
    }

    @Test fun adverbsButNotLookalikes() {
        assertEquals(listOf("quickly", "quietly"), found("She quickly and quietly left the family early.", IssueKind.ADVERB))
        assertTrue(found("The only ugly reply.", IssueKind.ADVERB).isEmpty())
    }

    @Test fun passiveVoice() {
        assertEquals(listOf("was written", "were quickly opened"), found("It was written here. Doors were quickly opened.", IssueKind.PASSIVE))
        assertTrue(found("He was. Taken aback, we need speed.", IssueKind.PASSIVE).isEmpty())
        assertTrue(found("She is tall and was here indeed.", IssueKind.PASSIVE).isEmpty())
    }

    @Test fun longSentencesComeFirst() {
        val long = (1..31).joinToString(" ") { "word" } + "."
        val text = "Short one. $long It was written."
        val issues = Style.check(text)
        assertEquals(IssueKind.LONG, issues.first().kind)
        assertEquals(long, text.substring(issues.first().start, issues.first().end))
        assertEquals(1, Style.count(issues, IssueKind.LONG))
        assertTrue(found((1..30).joinToString(" ") { "word" } + ".", IssueKind.LONG).isEmpty())
    }

    @Test fun rangesStayInsideTheText() {
        val t = "Really.\n\nIt is very, very\nbroken"
        for (i in Style.check(t)) assertTrue("$i", i.start in 0 until i.end && i.end <= t.length)
        assertTrue(Style.check("").isEmpty())
    }
}
