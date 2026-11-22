package dev.field.page

/** A half-open character range, [start, end). */
data class Span(val start: Int, val end: Int)

/** The result of a toolbar or auto-format edit: new text and selection. */
data class Edit(val text: String, val selStart: Int, val selEnd: Int)

/** Pure text helpers for PAGE: counts, focus ranges and Markdown edits. No Android. */
object Text {

    // ---- counts ----

    /** Words are runs of letters or digits; apostrophes and hyphens join inside a word. */
    fun words(text: String): Int {
        var count = 0
        var inWord = false
        for (i in text.indices) {
            val c = text[i]
            if (c.isLetterOrDigit()) {
                if (!inWord) count++
                inWord = true
            } else if (inWord && (c == '\'' || c == '’' || c == '-') &&
                i + 1 < text.length && text[i + 1].isLetterOrDigit()
            ) {
                // joiner: stay inside the word
            } else {
                inWord = false
            }
        }
        return count
    }

    /** Minutes at 230 words per minute, rounded up; 0 for an empty text. */
    fun readingMinutes(words: Int): Int = if (words <= 0) 0 else (words + 229) / 230

    /** First non-blank line without its Markdown markers, at most 60 characters. */
    fun title(text: String): String {
        var pos = 0
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            val line = text.substring(pos, nl).trim().trimStart('#', '>', '-', '*', '+', ' ', '\t').trim()
            if (line.isNotEmpty()) return if (line.length > 60) line.substring(0, 60).trimEnd() else line
            pos = nl + 1
        }
        return ""
    }

    /** A safe file name stem for exports. */
    fun fileName(title: String): String {
        val slug = title.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .split('-').filter { it.isNotEmpty() }.joinToString("-")
        return if (slug.isEmpty()) "untitled" else slug.take(48).trimEnd('-')
    }

    // ---- focus ranges ----

    /** The line (paragraph) holding [cursor]. */
    fun paragraph(text: String, cursor: Int): Span {
        val c = cursor.coerceIn(0, text.length)
        val start = if (c == 0) 0 else text.lastIndexOf('\n', c - 1) + 1
        val end = text.indexOf('\n', c).let { if (it < 0) text.length else it }
        return Span(start, end)
    }

    private fun isTerminator(c: Char) = c == '.' || c == '!' || c == '?' || c == '…'
    private fun isCloser(c: Char) = c == '"' || c == '\'' || c == ')' || c == ']' || c == '”' || c == '’' || c == '*' || c == '_'

    /** Sentences inside [range]: split at . ! ? (plus closing quotes) followed by whitespace. */
    private fun sentencesIn(text: String, range: Span, out: MutableList<Span>) {
        var start = range.start
        while (start < range.end && text[start].isWhitespace()) start++
        var i = start
        while (i < range.end) {
            if (isTerminator(text[i])) {
                var j = i + 1
                while (j < range.end && (isTerminator(text[j]) || isCloser(text[j]))) j++
                if (j >= range.end || text[j].isWhitespace()) {
                    out.add(Span(start, j))
                    var k = j
                    while (k < range.end && text[k].isWhitespace()) k++
                    start = k
                    i = k
                    continue
                }
                i = j
                continue
            }
            i++
        }
        if (start < range.end) out.add(Span(start, range.end))
    }

    /** Every sentence in the text, paragraph by paragraph. */
    fun sentences(text: String): List<Span> {
        val out = ArrayList<Span>()
        var pos = 0
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            sentencesIn(text, Span(pos, nl), out)
            pos = nl + 1
        }
        return out
    }

    /**
     * The sentence being written at [cursor]. A cursor in the gap after a
     * finished sentence still belongs to that sentence, so the line you just
     * ended stays lit until the next one has a first letter.
     */
    fun sentence(text: String, cursor: Int): Span {
        val p = paragraph(text, cursor)
        val c = cursor.coerceIn(0, text.length)
        val list = ArrayList<Span>()
        sentencesIn(text, p, list)
        return list.lastOrNull { it.start <= c } ?: list.firstOrNull() ?: p
    }

    // ---- Markdown edits ----

    /** Wraps the selection in [mark] (e.g. `**`), or unwraps it if already wrapped. */
    fun wrap(text: String, selStart: Int, selEnd: Int, mark: String): Edit {
        val s = minOf(selStart, selEnd).coerceIn(0, text.length)
        val e = maxOf(selStart, selEnd).coerceIn(0, text.length)
        val n = mark.length
        if (s >= n && text.startsWith(mark, s - n) && text.startsWith(mark, e)) {
            return Edit(text.removeRange(e, e + n).removeRange(s - n, s), s - n, e - n)
        }
        return Edit(text.substring(0, s) + mark + text.substring(s, e) + mark + text.substring(e), s + n, e + n)
    }

    /** Adds [prefix] (e.g. `- `) to the cursor's line, or removes it if present. */
    fun togglePrefix(text: String, cursor: Int, prefix: String): Edit {
        val c = cursor.coerceIn(0, text.length)
        val start = paragraph(text, c).start
        return if (text.startsWith(prefix, start)) {
            val at = maxOf(start, c - prefix.length)
            Edit(text.removeRange(start, start + prefix.length), at, at)
        } else {
            Edit(text.substring(0, start) + prefix + text.substring(start), c + prefix.length, c + prefix.length)
        }
    }

    private val headingPrefix = Regex("^(#{1,6})[ \\t]+")

    /** Cycles the cursor's line: body -> # -> ## -> ### -> body. */
    fun cycleHeading(text: String, cursor: Int): Edit {
        val c = cursor.coerceIn(0, text.length)
        val p = paragraph(text, c)
        val line = text.substring(p.start, p.end)
        val old = headingPrefix.find(line)
        val oldLen = old?.value?.length ?: 0
        val level = old?.groupValues?.get(1)?.length ?: 0
        val new = if (level >= 3) "" else "#".repeat(level + 1) + " "
        val at = maxOf(p.start + new.length, c - oldLen + new.length)
        return Edit(text.substring(0, p.start) + new + line.substring(oldLen) + text.substring(p.end), at, at)
    }

    private val listItem = Regex("^([ \\t]*)(?:([-*+])|(\\d{1,9})([.)]))([ \\t]+)(\\[[ xX]\\][ \\t]+)?(.*)$")
    private val quoteItem = Regex("^([ \\t]*>[ \\t]?)(.*)$")

    /**
     * Called after each change. If the change was a single Enter at the end of
     * a list or quote line, continues the list (numbers count up, checkboxes
     * reset); Enter on an empty item ends the list instead. Null = no change.
     */
    fun autoList(before: String, after: String, cursor: Int): Edit? {
        if (after.length != before.length + 1 || cursor < 1 || cursor > after.length) return null
        if (after[cursor - 1] != '\n') return null
        if (!after.regionMatches(0, before, 0, cursor - 1)) return null
        if (!after.regionMatches(cursor, before, cursor - 1, before.length - cursor + 1)) return null

        val lineStart = if (cursor < 2) 0 else before.lastIndexOf('\n', cursor - 2) + 1
        val line = before.substring(lineStart, cursor - 1)
        if (Markdown.rule.matches(line)) return null

        val prefix: String
        val content: String
        val item = listItem.matchEntire(line)
        if (item != null) {
            val (indent, bullet, number, delimiter, gap, box, rest) = item.destructured
            val marker = if (bullet.isNotEmpty()) bullet else (number.toLong() + 1).toString() + delimiter
            prefix = indent + marker + gap + if (box.isNotEmpty()) "[ ] " else ""
            content = rest
        } else {
            val quote = quoteItem.matchEntire(line) ?: return null
            prefix = quote.groupValues[1]
            content = quote.groupValues[2]
        }
        if (content.isBlank()) {
            return Edit(before.substring(0, lineStart) + before.substring(cursor - 1), lineStart, lineStart)
        }
        val at = cursor + prefix.length
        return Edit(after.substring(0, cursor) + prefix + after.substring(cursor), at, at)
    }
}
