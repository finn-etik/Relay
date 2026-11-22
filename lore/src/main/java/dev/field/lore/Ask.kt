package dev.field.lore

/** Where a quoted passage comes from. */
enum class Origin { NOTE, PACK }

/**
 * One numbered passage handed to the model and shown under the answer.
 * [ref] is what the app needs to open the whole thing again: a note's
 * document URI, or `pack id` + TAB + `article title`.
 */
data class Source(val origin: Origin, val title: String, val section: String, val text: String, val ref: String) {
    val label: String get() = if (section.isEmpty() || section == title) title else "$title · $section"
}

/** An article fetched from a knowledge pack, cut into passages. */
data class Article(val title: String, val pack: String, val ref: String, val passages: List<Passage>)

/** A passage of one of the user's notes. */
data class NoteChunk(val file: String, val ref: String, val passage: Passage)

/** The search index over the notes folder. */
class NotesIndex(val chunks: List<NoteChunk>) {
    private val index = Bm25(chunks.map { Words.tokens("${it.file} ${it.passage.heading} ${it.passage.text}") })

    /**
     * The passages that answer [question] best. A passage must contain at
     * least half of the question's meaningful words, and score at least
     * half as well as the best one: an unrelated note is worse than none.
     */
    fun search(question: String, limit: Int): List<NoteChunk> {
        val terms = Words.terms(question)
        if (terms.isEmpty()) return emptyList()
        val need = (terms.size + 1) / 2
        val hits = index.search(terms, limit * 4).filter { it.matched >= need }
        val best = hits.firstOrNull()?.score ?: return emptyList()
        return hits.filter { it.score >= best / 2 }.take(limit).map { chunks[it.doc] }
    }
}

/**
 * From a question to the text the model sees: which articles to look up,
 * which passages to quote, and how the prompt is worded. Pure functions;
 * the store supplies the archives and the model.
 */
object Ask {
    private const val MAX_GRAM = 4
    private const val MAX_LOOKUPS = 60

    /** A run of the question's words that may be an article title, in the spellings worth trying. */
    data class Candidate(val from: Int, val to: Int, val spellings: List<String>)

    /**
     * Possible article titles in [question]: every run of one to four words
     * that neither starts nor ends with a filler word, longest first.
     * Wikipedia titles are case-sensitive after the first letter, so each
     * run is tried as typed, with a capital first letter, and in Title Case.
     */
    fun candidates(question: String): List<Candidate> {
        val words = question.split(Regex("\\s+")).map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        val out = ArrayList<Candidate>()
        for (n in minOf(MAX_GRAM, words.size) downTo 1) {
            for (from in 0..words.size - n) {
                val run = words.subList(from, from + n)
                if (filler(run.first()) || filler(run.last())) continue
                if (n == 1 && run[0].length < 3) continue
                val typed = run.joinToString(" ")
                val spellings = listOf(
                    typed,
                    typed.replaceFirstChar { it.uppercaseChar() },
                    run.joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } },
                    typed.lowercase().replaceFirstChar { it.uppercaseChar() },
                ).distinct()
                out += Candidate(from, from + n, spellings)
            }
        }
        return out
    }

    private fun filler(word: String) = word.lowercase().substringBefore('\'') in Words.STOP

    /**
     * Looks the question's candidate titles up with [find] and returns up to
     * [max] distinct articles. A word already covered by a longer title is
     * not looked up on its own ("Marie Curie" found: no "Marie", no "Curie").
     */
    fun articles(question: String, max: Int, find: (String) -> Article?): List<Article> {
        val found = ArrayList<Article>()
        val covered = HashSet<Int>()
        var lookups = 0
        for (c in candidates(question)) {
            if (found.size >= max || lookups >= MAX_LOOKUPS) break
            if ((c.from until c.to).any { it in covered }) continue
            for (spelling in c.spellings) {
                lookups++
                val article = find(spelling) ?: continue
                if (found.none { it.ref == article.ref } && article.passages.isNotEmpty()) found += article
                covered += c.from until c.to
                break
            }
        }
        return found
    }

    /** Asks the model which articles to open, for when no title can be read off the question itself. */
    const val TITLE_SYSTEM = "You name encyclopedia articles. Reply with article titles only, one per line."

    fun titlePrompt(question: String): String =
        "Which Wikipedia articles would answer this question? Give up to three exact article titles, one per line, nothing else.\n\nQuestion: ${question.trim()}"

    /** The titles in the model's reply to [titlePrompt]: list markers, quotes and chatter removed. */
    fun titlesFrom(reply: String): List<String> = reply.lineSequence()
        .map { it.trim().replace(Regex("^(?:[-*•]|\\d+[.)])\\s*"), "").trim('"', '\'', '*', '`', '_', ' ', '.') }
        .filter { it.length in 2..60 && !it.endsWith(":") && it.count { c -> c == ' ' } < 7 }
        .distinct().take(3).toList()

    /** The articles for titles the model proposed, tried as given and with a capital first letter. */
    fun articlesNamed(titles: List<String>, find: (String) -> Article?): List<Article> = titles
        .mapNotNull { t -> listOf(t, t.replaceFirstChar { it.uppercaseChar() }).distinct().firstNotNullOfOrNull(find) }
        .filter { it.passages.isNotEmpty() }
        .distinctBy { it.ref }

    /**
     * The passages of [articles] worth quoting for [question], within
     * [budget] characters: the opening of the best-matching article first
     * (it says what the thing is), then whatever ranks highest.
     */
    fun fromArticles(question: String, articles: List<Article>, budget: Int): List<Source> {
        data class Item(val article: Article, val passage: Passage, val lead: Boolean)
        val items = articles.flatMap { a -> a.passages.mapIndexed { i, p -> Item(a, p, i == 0) } }
        if (items.isEmpty()) return emptyList()
        val index = Bm25(items.map { Words.tokens("${it.article.title} ${it.passage.heading} ${it.passage.text}") })
        val ranked = index.search(Words.terms(question), 12).map { items[it.doc] }
        val order = (listOf(items.first()) + ranked + items.filter { it.lead }).distinct()
        return fill(order.map { Source(Origin.PACK, it.article.title, it.passage.heading, it.passage.text, it.article.ref) }, budget)
    }

    fun fromNotes(question: String, notes: NotesIndex, budget: Int, limit: Int = 3): List<Source> =
        fill(notes.search(question, limit).map { Source(Origin.NOTE, it.file, it.passage.heading, it.passage.text, it.ref) }, budget)

    /** Takes sources in order until [budget] characters are used; the last one may be cut short. */
    private fun fill(sources: List<Source>, budget: Int): List<Source> {
        val out = ArrayList<Source>()
        var left = budget
        for (s in sources) {
            if (left < 200) break
            val text = if (s.text.length <= left) s.text else s.text.take(left).substringBeforeLast(' ') + " …"
            out += s.copy(text = text)
            left -= text.length
        }
        return out
    }

    fun system(today: String): String =
        "You are LORE, an assistant running on the user's phone without internet. Answer briefly and plainly. " +
            "When a message comes with numbered sources, base your answer on them and cite the ones you use as [1], [2]. " +
            "If the sources do not answer the question, say so first; whatever you then add from memory may be wrong, so mark it as from memory. " +
            "Never invent a source or a quotation. Today is $today."

    /** The user message: the sources, numbered from 1, then the question. */
    fun prompt(question: String, sources: List<Source>): String {
        if (sources.isEmpty()) return question.trim()
        val out = StringBuilder("Sources:\n\n")
        sources.forEachIndexed { i, s ->
            val kind = if (s.origin == Origin.NOTE) "my notes" else "encyclopedia"
            out.append('[').append(i + 1).append("] ").append(s.label).append(" (").append(kind).append(")\n").append(s.text).append("\n\n")
        }
        return out.append("Question: ").append(question.trim()).toString()
    }

    /**
     * The latest exchanges that fit in [budget] characters, oldest first,
     * as (question, answer) pairs. Sources of earlier turns are not resent.
     */
    fun history(turns: List<Pair<String, String>>, budget: Int): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var left = budget
        for ((q, a) in turns.asReversed()) {
            if (a.isBlank()) continue
            val cost = q.length + a.length
            if (cost > left) break
            out += q to a
            left -= cost
        }
        return out.asReversed()
    }

    /**
     * What to show of the model's raw output: without its private reasoning
     * (`<think>` blocks, which some models emit, complete or still open).
     */
    fun clean(raw: String): String {
        var text = raw
        while (true) {
            val open = text.indexOf("<think>")
            if (open < 0) break
            val close = text.indexOf("</think>", open)
            text = if (close < 0) text.substring(0, open) else text.substring(0, open) + text.substring(close + 8)
        }
        return text.trim()
    }

    /** A chat's name: the start of its first question. */
    fun chatTitle(question: String): String {
        val line = question.trim().lineSequence().firstOrNull().orEmpty().replace(Regex("\\s+"), " ")
        return if (line.length <= 48) line else line.take(48).substringBeforeLast(' ') + "…"
    }
}
