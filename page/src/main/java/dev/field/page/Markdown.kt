package dev.field.page

enum class MdKind { HEADING, BOLD, ITALIC, STRIKE, CODE, QUOTE, LINK, MARK }

/** A styled range of the raw text. MARK = syntax characters, drawn dim. */
data class MdSpan(val start: Int, val end: Int, val kind: MdKind)

/**
 * A small Markdown scanner for the editor: it never changes the text, it only
 * says which ranges are headings, emphasis, code and syntax marks. Pure.
 */
object Markdown {
    internal val heading = Regex("^(#{1,6})(?:[ \\t]+(.*))?$")
    internal val quote = Regex("^[ \\t]*(>+)[ \\t]?(.*)$")
    internal val list = Regex("^[ \\t]*([-*+]|\\d{1,9}[.)])[ \\t]+(\\[[ xX]\\][ \\t]+)?(.*)$")
    internal val rule = Regex("^[ \\t]*([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$")
    internal val code = Regex("`[^`\\n]+`")
    internal val bold = Regex("(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1")
    internal val italic = Regex("(?<![*_\\p{L}\\p{N}])([*_])(?=[^\\s*_])(.+?)(?<=[^\\s*_])\\1(?![*_\\p{L}\\p{N}])")
    internal val strike = Regex("~~(?=\\S)(.+?)(?<=\\S)~~")
    internal val link = Regex("\\[([^\\]\\n]+)\\]\\(([^)\\s]+)\\)")

    internal fun isFence(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    fun spans(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        var pos = 0
        var fenced = false
        while (pos <= text.length) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val line = text.substring(pos, nl)
            when {
                isFence(line) -> {
                    out.add(MdSpan(pos, nl, MdKind.MARK))
                    fenced = !fenced
                }
                fenced -> if (line.isNotEmpty()) out.add(MdSpan(pos, nl, MdKind.CODE))
                line.isEmpty() -> {}
                rule.matches(line) -> out.add(MdSpan(pos, nl, MdKind.MARK))
                else -> {
                    block(line, pos, out)
                    inline(line, pos, out)
                }
            }
            pos = nl + 1
        }
        return out
    }

    private fun block(line: String, pos: Int, out: MutableList<MdSpan>) {
        val h = heading.matchEntire(line)
        if (h != null) {
            out.add(MdSpan(pos, pos + line.length, MdKind.HEADING))
            out.add(MdSpan(pos, pos + h.groupValues[1].length, MdKind.MARK))
            return
        }
        val q = quote.matchEntire(line)
        if (q != null) {
            val marks = q.groups[1]!!.range
            out.add(MdSpan(pos, pos + line.length, MdKind.QUOTE))
            out.add(MdSpan(pos + marks.first, pos + marks.last + 1, MdKind.MARK))
            return
        }
        val l = list.matchEntire(line)
        if (l != null) {
            val content = l.groups[3]!!.range.first
            out.add(MdSpan(pos, pos + content, MdKind.MARK))
        }
    }

    private fun inline(line: String, pos: Int, out: MutableList<MdSpan>) {
        val taken = BooleanArray(line.length)
        fun free(r: IntRange) = !taken[r.first] && !taken[r.last]
        fun take(r: IntRange) { for (i in r) taken[i] = true }

        for (m in code.findAll(line)) {
            out.add(MdSpan(pos + m.range.first, pos + m.range.last + 1, MdKind.CODE))
            take(m.range)
        }
        for (m in link.findAll(line)) {
            if (!free(m.range)) continue
            val label = m.groups[1]!!.range
            out.add(MdSpan(pos + label.first, pos + label.last + 1, MdKind.LINK))
            out.add(MdSpan(pos + m.range.first, pos + label.first, MdKind.MARK))
            out.add(MdSpan(pos + label.last + 1, pos + m.range.last + 1, MdKind.MARK))
            // The URL is not prose: keep emphasis rules out of it.
            take(label.last + 1..m.range.last)
        }
        emphasis(bold, MdKind.BOLD, 2, line, pos, taken, out)
        emphasis(strike, MdKind.STRIKE, 2, line, pos, taken, out)
        emphasis(italic, MdKind.ITALIC, 1, line, pos, taken, out)
    }

    private fun emphasis(
        regex: Regex, kind: MdKind, mark: Int,
        line: String, pos: Int, taken: BooleanArray, out: MutableList<MdSpan>,
    ) {
        for (m in regex.findAll(line)) {
            val r = m.range
            if (taken[r.first] || taken[r.last]) continue
            out.add(MdSpan(pos + r.first + mark, pos + r.last + 1 - mark, kind))
            out.add(MdSpan(pos + r.first, pos + r.first + mark, MdKind.MARK))
            out.add(MdSpan(pos + r.last + 1 - mark, pos + r.last + 1, MdKind.MARK))
        }
    }
}
