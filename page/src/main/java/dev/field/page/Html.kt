package dev.field.page

/** Markdown to HTML for export and printing. Covers what the editor highlights. Pure. */
object Html {

    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    /** Only plain web, mail and in-page links become anchors; anything else stays text. */
    private fun safeUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:") ||
            u.startsWith("#") || u.startsWith("/")
    }

    private fun emphasis(escaped: String): String {
        var s = Markdown.link.replace(escaped) { m ->
            val (label, url) = m.destructured
            if (safeUrl(url)) "<a href=\"$url\">$label</a>" else label
        }
        s = Markdown.bold.replace(s) { "<strong>" + it.groupValues[2] + "</strong>" }
        s = Markdown.strike.replace(s) { "<del>" + it.groupValues[1] + "</del>" }
        s = Markdown.italic.replace(s) { "<em>" + it.groupValues[2] + "</em>" }
        return s
    }

    fun inline(s: String): String {
        val out = StringBuilder()
        var last = 0
        for (m in Markdown.code.findAll(s)) {
            out.append(emphasis(escape(s.substring(last, m.range.first))))
            out.append("<code>").append(escape(m.value.substring(1, m.value.length - 1))).append("</code>")
            last = m.range.last + 1
        }
        out.append(emphasis(escape(s.substring(last))))
        return out.toString()
    }

    /** Each written line is its own paragraph, as it looks in the editor. */
    fun body(markdown: String): String {
        val lines = markdown.split('\n')
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                Markdown.isFence(line) -> {
                    i++
                    out.append("<pre><code>")
                    while (i < lines.size && !Markdown.isFence(lines[i])) {
                        out.append(escape(lines[i])).append('\n')
                        i++
                    }
                    out.append("</code></pre>\n")
                    i++
                }
                line.isBlank() -> i++
                Markdown.rule.matches(line) -> {
                    out.append("<hr>\n")
                    i++
                }
                Markdown.heading.matches(line) -> {
                    val m = Markdown.heading.matchEntire(line)!!
                    val level = m.groupValues[1].length
                    val content = m.groupValues[2].trim().trimEnd('#').trim()
                    out.append("<h$level>").append(inline(content)).append("</h$level>\n")
                    i++
                }
                Markdown.quote.matches(line) -> {
                    out.append("<blockquote>\n")
                    while (i < lines.size) {
                        val q = Markdown.quote.matchEntire(lines[i]) ?: break
                        val content = q.groupValues[2].trim()
                        if (content.isNotEmpty()) out.append("<p>").append(inline(content)).append("</p>\n")
                        i++
                    }
                    out.append("</blockquote>\n")
                }
                Markdown.list.matches(line) -> {
                    val ordered = Markdown.list.matchEntire(line)!!.groupValues[1][0].isDigit()
                    val tag = if (ordered) "ol" else "ul"
                    out.append("<$tag>\n")
                    while (i < lines.size) {
                        if (Markdown.rule.matches(lines[i])) break
                        val l = Markdown.list.matchEntire(lines[i]) ?: break
                        val box = l.groupValues[2].trim()
                        val check = when {
                            box.isEmpty() -> ""
                            box == "[ ]" -> "☐ "
                            else -> "☑ "
                        }
                        out.append("<li>").append(check).append(inline(l.groupValues[3].trim())).append("</li>\n")
                        i++
                    }
                    out.append("</$tag>\n")
                }
                else -> {
                    out.append("<p>").append(inline(line.trim())).append("</p>\n")
                    i++
                }
            }
        }
        return out.toString()
    }

    fun document(title: String, markdown: String): String =
        "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "<title>" + escape(title.ifEmpty { "Untitled" }) + "</title>\n" +
            "<style>body{max-width:38em;margin:2em auto;padding:0 1em;font-family:Georgia,serif;" +
            "font-size:12pt;line-height:1.6;color:#191919}h1,h2,h3,h4,h5,h6{line-height:1.25}" +
            "code,pre{font-family:monospace;font-size:.9em}pre{white-space:pre-wrap}" +
            "blockquote{margin-left:0;padding-left:1em;border-left:3px solid #c4c4bc;color:#555}" +
            "hr{border:0;border-top:1px solid #c4c4bc}</style>\n</head>\n<body>\n" +
            body(markdown) + "</body>\n</html>\n"

    /** Markdown with its syntax removed, for plain-text export. */
    fun plain(markdown: String): String = markdown.split('\n').joinToString("\n") { line ->
        when {
            Markdown.isFence(line) -> ""
            Markdown.rule.matches(line) -> ""
            else -> {
                var s = line
                Markdown.heading.matchEntire(s)?.let { s = it.groupValues[2] }
                s = Markdown.link.replace(s) { it.groupValues[1] }
                s = Markdown.bold.replace(s) { it.groupValues[2] }
                s = Markdown.strike.replace(s) { it.groupValues[1] }
                s = Markdown.italic.replace(s) { it.groupValues[2] }
                s
            }
        }
    }
}
