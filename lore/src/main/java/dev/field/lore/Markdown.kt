package dev.field.lore

/**
 * The Markdown a small model writes, parsed for display: paragraphs,
 * headings, lists, code, and inline emphasis. `[2]` is kept as a citation
 * so the screen can tint it. Anything else is shown as typed.
 */
object Markdown {
    data class Span(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false, val cite: Int = 0)

    sealed interface Block
    data class Para(val spans: List<Span>) : Block
    data class Heading(val level: Int, val spans: List<Span>) : Block
    data class Item(val marker: String, val depth: Int, val spans: List<Span>) : Block
    data class Code(val text: String) : Block

    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)([-*+•])\\s+(.*)$")
    private val NUMBER = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")

    fun blocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        var code: StringBuilder? = null
        fun flush() {
            if (para.isNotBlank()) out += Para(spans(para.toString().trim()))
            para.setLength(0)
        }
        for (line in text.lines()) {
            val fence = line.trimStart().startsWith("```")
            val open = code
            if (open != null) {
                if (fence) { out += Code(open.toString().trimEnd('\n')); code = null } else open.append(line).append('\n')
                continue
            }
            if (fence) { flush(); code = StringBuilder(); continue }
            if (line.isBlank()) { flush(); continue }
            val heading = HEADING.find(line)
            val bullet = BULLET.find(line)
            val number = NUMBER.find(line)
            when {
                heading != null -> { flush(); out += Heading(heading.groupValues[1].length, spans(heading.groupValues[2].trim().trimEnd('#').trim())) }
                bullet != null -> { flush(); out += Item("•", bullet.groupValues[1].length / 2, spans(bullet.groupValues[3])) }
                number != null -> { flush(); out += Item(number.groupValues[2] + ".", number.groupValues[1].length / 2, spans(number.groupValues[3])) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(line.trim()) }
            }
        }
        // An answer still being written may end inside a code block.
        code?.let { out += Code(it.toString().trimEnd('\n')) }
        flush()
        return out
    }

    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        val plain = StringBuilder()
        var bold = false
        var italic = false
        fun flush() {
            if (plain.isNotEmpty()) out += Span(plain.toString(), bold, italic)
            plain.setLength(0)
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) { plain.append(c); i++ } else { flush(); out += Span(text.substring(i + 1, end), code = true); i = end + 1 }
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    // A marker opens only if it closes later; otherwise it is just two characters.
                    if (bold || text.indexOf(text.substring(i, i + 2), i + 2) >= 0) { flush(); bold = !bold } else plain.append(text, i, i + 2)
                    i += 2
                }
                c == '*' || (c == '_' && wordEdge(text, i)) -> {
                    if (italic || closes(text, c, i)) { flush(); italic = !italic } else plain.append(c)
                    i++
                }
                c == '[' -> {
                    val end = text.indexOf(']', i + 1)
                    val inner = if (end < 0) "" else text.substring(i + 1, end)
                    val number = inner.toIntOrNull()
                    when {
                        number != null && number in 1..99 -> { flush(); out += Span("[$inner]", cite = number); i = end + 1 }
                        end > 0 && end + 1 < text.length && text[end + 1] == '(' && text.indexOf(')', end + 2) > 0 -> {
                            plain.append(inner) // a link: keep its text, drop the address
                            i = text.indexOf(')', end + 2) + 1
                        }
                        else -> { plain.append(c); i++ }
                    }
                }
                else -> { plain.append(c); i++ }
            }
        }
        flush()
        return out
    }

    /** An underscore is emphasis only at the edge of a word (snake_case is not). */
    private fun wordEdge(text: String, at: Int): Boolean {
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + 1)
        return before == null || after == null || !before.isLetterOrDigit() || !after.isLetterOrDigit()
    }

    private fun closes(text: String, marker: Char, at: Int): Boolean {
        val next = text.getOrNull(at + 1) ?: return false
        if (next.isWhitespace()) return false // "2 * 3", or a bullet
        val end = text.indexOf(marker, at + 1)
        return end > at + 1 && !text[end - 1].isWhitespace()
    }
}
