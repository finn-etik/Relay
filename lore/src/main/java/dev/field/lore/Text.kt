package dev.field.lore

/** Words as the search index sees them. Pure Kotlin, no Android. */
object Words {
    /** Too common to tell one text from another; also the words questions are made of. */
    val STOP: Set<String> = (
        "a an the and or but if then else of to in on at by for from with without about into over under between " +
            "is are was were be been being am do does did done doing have has had having will would shall should can could may might must " +
            "i me my mine we us our you your he him his she her it its they them their this that these those there here " +
            "what which who whom whose when where why how not no nor so than too very just also as up out off again more most some any each " +
            "tell explain describe give show please mean means meaning define definition"
        ).split(' ').toSet()

    /** Lower-case words of letters and digits, in order, with duplicates. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.length >= 2 || (word.length == 1 && word[0].isDigit())) out += stem(word.toString())
            word.setLength(0)
        }
        for (c in text) {
            if (c.isLetterOrDigit()) word.append(c.lowercaseChar()) else flush()
        }
        flush()
        return out
    }

    /** The distinct words of a question that carry meaning. */
    fun terms(text: String): List<String> = tokens(text).filter { it !in STOP }.distinct()

    /** Folds plain English plurals, nothing more: a wrong stem costs more than a missed one. */
    fun stem(word: String): String = when {
        word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
        word.length > 4 && word.endsWith("sses") -> word.dropLast(2)
        word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is") -> word.dropLast(1)
        else -> word
    }
}

/** A stretch of text under a heading: the unit that is searched, ranked and quoted. */
data class Passage(val heading: String, val text: String)

/** Turns an article's HTML (as stored in a ZIM archive) into plain text blocks. */
object Html {
    data class Block(val heading: Boolean, val text: String)

    private val SKIP_TAGS = setOf("script", "style", "table", "figure", "nav", "footer", "head", "noscript", "svg", "audio", "video", "button", "form", "annotation")
    private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "wbr", "source", "col", "area", "base", "embed", "track")
    private val BLOCK = setOf(
        "p", "div", "li", "ul", "ol", "dl", "dd", "dt", "blockquote", "section", "article", "details", "summary",
        "pre", "tr", "br", "hr", "body", "main", "header", "center",
    )
    private val HEADINGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")

    /** Page furniture: citations, navigation boxes, edit links, image captions. */
    private val SKIP_CLASS = listOf(
        "reference", "reflist", "navbox", "infobox", "mw-editsection", "noprint", "hatnote", "thumb", "sidebar",
        "metadata", "toc", "mw-cite-backlink", "catlinks", "shortdescription", "mw-empty-elt", "printfooter",
    )

    fun blocks(html: String): List<Block> {
        val out = ArrayList<Block>()
        val text = StringBuilder()
        var heading = false
        var skipTag: String? = null
        var depth = 0

        fun flush(asHeading: Boolean) {
            val t = clean(text)
            text.setLength(0)
            if (t.isNotEmpty()) out += Block(asHeading, t)
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c != '<') {
                if (skipTag == null) text.append(c)
                i++
                continue
            }
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4)
                i = if (end < 0) n else end + 3
                continue
            }
            val close = html.indexOf('>', i + 1)
            if (close < 0) break
            val closing = i + 1 < n && html[i + 1] == '/'
            var j = if (closing) i + 2 else i + 1
            val nameStart = j
            while (j < close && (html[j].isLetterOrDigit())) j++
            val name = html.substring(nameStart, j).lowercase()
            if (name.isEmpty()) { // "<" in running text, a doctype, a processing instruction
                if (skipTag == null && !closing && html[i + 1] != '!' && html[i + 1] != '?') { text.append('<'); i++; continue }
                i = close + 1
                continue
            }
            val selfClosed = html[close - 1] == '/' || name in VOID
            if (skipTag != null) {
                if (name == skipTag) {
                    if (closing) depth-- else if (!selfClosed) depth++
                    if (depth <= 0) skipTag = null
                }
            } else if (!closing && name == "math") {
                // Formulas keep their TeX source, the one readable form in plain text.
                attribute(html.substring(i, close), "alttext")?.let { text.append(' ').append(tex(it)).append(' ') }
                skipTag = name; depth = 1
            } else if (!closing && !selfClosed && (name in SKIP_TAGS || furniture(html.substring(i, close)))) {
                skipTag = name; depth = 1
            } else if (name in HEADINGS) {
                if (closing) { flush(heading); heading = false } else { flush(false); heading = true }
            } else if (name in BLOCK) {
                flush(heading && name != "br")
                if (name != "br") heading = false
            }
            i = close + 1
        }
        flush(heading)
        return out
    }

    private fun furniture(tag: String): Boolean {
        val value = attribute(tag, "class") ?: return false
        return SKIP_CLASS.any { value.contains(it) }
    }

    /** The value of attribute [name] in the text of one opening tag (without its closing bracket). */
    private fun attribute(tag: String, name: String): String? {
        var at = tag.indexOf("$name=")
        while (at > 0 && !tag[at - 1].isWhitespace()) at = tag.indexOf("$name=", at + 1)
        if (at <= 0) return null
        val start = at + name.length + 1
        if (start >= tag.length) return null
        val quote = tag[start]
        if (quote != '"' && quote != '\'') return tag.substring(start).substringBefore(' ')
        val end = tag.indexOf(quote, start + 1)
        return if (end < 0) null else tag.substring(start + 1, end)
    }

    private fun tex(alt: String): String =
        decode(alt).trim().removePrefix("{\\displaystyle").removeSuffix("}").trim()

    private fun clean(raw: CharSequence): String {
        val decoded = decode(raw.toString())
        val out = StringBuilder(decoded.length)
        var space = false
        for (c in decoded) {
            if (c.isWhitespace() || c == ' ') {
                space = out.isNotEmpty()
            } else {
                if (space) out.append(' ')
                space = false
                out.append(c)
            }
        }
        return out.toString()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "minus" to "−", "times" to "×", "deg" to "°",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "thinsp" to " ", "ensp" to " ", "emsp" to " ",
    )

    fun decode(text: String): String {
        if ('&' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val end = if (c == '&') text.indexOf(';', i + 1) else -1
            if (end < 0 || end - i > 10) { out.append(c); i++; continue }
            val name = text.substring(i + 1, end)
            val value: String? = when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[name]
            }
            if (value == null) { out.append(c); i++ } else { out.append(value); i = end + 1 }
        }
        return out.toString()
    }

    private fun codePoint(code: Int): String? =
        if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null
}

object Passages {
    /** Sections at the end of an encyclopedia article that hold no prose. */
    private val END = setOf(
        "references", "external links", "see also", "notes", "further reading", "bibliography", "citations",
        "sources", "footnotes", "works cited", "notes and references",
    )

    /**
     * Groups [blocks] into passages of about [size] characters, each under
     * the heading it appeared below. [article] drops reference sections.
     */
    fun of(blocks: List<Html.Block>, size: Int = 700, article: Boolean = true): List<Passage> {
        val out = ArrayList<Passage>()
        var heading = ""
        var skipping = false
        val text = StringBuilder()
        fun flush() {
            if (text.isNotBlank()) out += Passage(heading, text.toString().trim())
            text.setLength(0)
        }
        for (b in blocks) {
            if (b.heading) {
                flush()
                heading = b.text
                skipping = article && b.text.lowercase() in END
                continue
            }
            if (skipping) continue
            for (piece in split(b.text, size)) {
                if (text.isNotEmpty() && text.length + piece.length > size) flush()
                if (text.isNotEmpty()) text.append('\n')
                text.append(piece)
            }
        }
        flush()
        return out
    }

    /** A note (Markdown or plain text) as passages: `#` lines are headings, blank lines end paragraphs. */
    fun ofNote(note: String, size: Int = 700): List<Passage> {
        val blocks = ArrayList<Html.Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) blocks += Html.Block(false, para.toString().trim())
            para.setLength(0)
        }
        for (line in note.lineSequence()) {
            val t = line.trim()
            when {
                t.isEmpty() -> flush()
                t.startsWith("#") -> { flush(); blocks += Html.Block(true, t.trimStart('#').trim()) }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
            }
        }
        flush()
        return of(blocks.filter { it.text.isNotEmpty() }, size, article = false)
    }

    /** Cuts a paragraph longer than [size] at sentence ends (or, failing that, at spaces). */
    internal fun split(text: String, size: Int): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        var from = 0
        while (text.length - from > size) {
            val window = text.substring(from, from + size)
            var cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! ")) + 1
            if (cut < size / 3) cut = window.lastIndexOf(' ')
            if (cut < size / 3) cut = size
            out += text.substring(from, from + cut).trim()
            from += cut
        }
        text.substring(from).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }
}
