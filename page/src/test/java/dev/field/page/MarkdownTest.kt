package dev.field.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    private fun kinds(text: String) = Markdown.spans(text).map { Triple(text.substring(it.start, it.end), it.kind, it.start) }
    private fun has(text: String, piece: String, kind: MdKind) =
        assertTrue("$piece as $kind in ${kinds(text)}", kinds(text).any { it.first == piece && it.second == kind })

    @Test fun headingAndItsMark() {
        has("## Title", "## Title", MdKind.HEADING)
        has("## Title", "##", MdKind.MARK)
        assertTrue(Markdown.spans("#hashtag").isEmpty())
    }

    @Test fun emphasisAndMarks() {
        val t = "a **bold** and *it* and ~~no~~ and `co*de*`"
        has(t, "bold", MdKind.BOLD)
        has(t, "it", MdKind.ITALIC)
        has(t, "no", MdKind.STRIKE)
        has(t, "`co*de*`", MdKind.CODE)
        assertEquals(1, Markdown.spans(t).count { it.kind == MdKind.ITALIC }) // none inside code
        assertEquals(0, Markdown.spans("2 * 3 * 4 and snake_case_name").count { it.kind == MdKind.ITALIC })
    }

    @Test fun linksListsQuotesRulesFences() {
        has("see [docs](https://a.b/c_d_e)", "docs", MdKind.LINK)
        assertEquals(0, Markdown.spans("see [docs](https://a.b/c_d_e)").count { it.kind == MdKind.ITALIC })
        has("- [ ] task", "- [ ] ", MdKind.MARK)
        has("12. item", "12. ", MdKind.MARK)
        has("> quoted", "> quoted", MdKind.QUOTE)
        has("---", "---", MdKind.MARK)
        val fenced = "```\n# not a heading\n```\n# heading"
        has(fenced, "# not a heading", MdKind.CODE)
        assertEquals(1, Markdown.spans(fenced).count { it.kind == MdKind.HEADING })
    }

    @Test fun spansStayInsideTheText() {
        val t = "# h\n\n**b** _i_ [l](u)\n- x\n> q\n```\ncode"
        for (s in Markdown.spans(t)) assertTrue("$s", s.start in 0..s.end && s.end <= t.length)
        assertTrue(Markdown.spans("").isEmpty())
    }

    @Test fun htmlBlocks() {
        val html = Html.body("# Title\n\nOne *two* **three**.\nNext & <b>.\n\n- a\n- [x] b\n\n1. one\n\n> q\n\n---\n```\n<x>\n```")
        assertEquals(
            "<h1>Title</h1>\n<p>One <em>two</em> <strong>three</strong>.</p>\n<p>Next &amp; &lt;b&gt;.</p>\n" +
                "<ul>\n<li>a</li>\n<li>☑ b</li>\n</ul>\n<ol>\n<li>one</li>\n</ol>\n" +
                "<blockquote>\n<p>q</p>\n</blockquote>\n<hr>\n<pre><code>&lt;x&gt;\n</code></pre>\n",
            html,
        )
    }

    @Test fun htmlLinksAreEscapedAndFiltered() {
        assertEquals("<a href=\"https://a.b/?x=1&amp;y=2\">go</a>", Html.inline("[go](https://a.b/?x=1&y=2)"))
        assertEquals("go", Html.inline("[go](javascript:alert(1))").substringBefore(")"))
        assertEquals("<code>&lt;i&gt; *x*</code>", Html.inline("`<i> *x*`"))
        assertTrue(Html.document("A <b>", "x").contains("<title>A &lt;b&gt;</title>"))
    }

    @Test fun plainTextDropsSyntax() {
        assertEquals("Title\nA bold link.\n\n- item", Html.plain("## Title\nA **bold** [link](https://x.y).\n---\n- item"))
    }
}
