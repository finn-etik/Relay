package dev.field.lore

import dev.field.lore.Markdown.Code
import dev.field.lore.Markdown.Heading
import dev.field.lore.Markdown.Item
import dev.field.lore.Markdown.Para
import dev.field.lore.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun blocks() {
        val text = "## Title\nFirst line\nsecond line.\n\n- one\n  - nested\n2. two\n\n```kotlin\nval x = 1\n```\nAfter"
        assertEquals(
            listOf(
                Heading(2, listOf(Span("Title"))),
                Para(listOf(Span("First line second line."))),
                Item("•", 0, listOf(Span("one"))),
                Item("•", 1, listOf(Span("nested"))),
                Item("2.", 0, listOf(Span("two"))),
                Code("val x = 1"),
                Para(listOf(Span("After"))),
            ),
            Markdown.blocks(text),
        )
    }

    @Test fun anUnfinishedCodeBlockStillShows() {
        assertEquals(listOf(Code("val x")), Markdown.blocks("```\nval x"))
    }

    @Test fun inline() {
        assertEquals(
            listOf(Span("Born in "), Span("Warsaw", bold = true), Span(" "), Span("[1]", cite = 1), Span(", "), Span("not", italic = true), Span(" Paris; see "), Span("code_here", code = true), Span(".")),
            Markdown.spans("Born in **Warsaw** [1], *not* Paris; see `code_here`."),
        )
    }

    @Test fun strayMarkersStayText() {
        assertEquals(listOf(Span("2 * 3 = 6 and snake_case and [note] and a ` tick")), Markdown.spans("2 * 3 = 6 and snake_case and [note] and a ` tick"))
        assertEquals(listOf(Span("unclosed **bold")), Markdown.spans("unclosed **bold"))
        assertEquals(listOf(Span("see the docs now")), Markdown.spans("see [the docs](https://example.org) now"))
    }
}
