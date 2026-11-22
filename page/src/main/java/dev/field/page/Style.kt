package dev.field.page

enum class IssueKind { LONG, FILLER, ADVERB, PASSIVE }

data class Issue(val start: Int, val end: Int, val kind: IssueKind)

/**
 * STYLE CHECK: plain word-list heuristics for English prose, run on the
 * device. It marks candidates to reconsider, not errors. Pure.
 */
object Style {
    /** Sentences above this many words are marked LONG. */
    const val LONG_WORDS = 30

    private val word = Regex("\\p{L}[\\p{L}'’-]*")

    private val fillers = setOf(
        "just", "really", "very", "quite", "actually", "basically", "literally", "simply",
        "rather", "somewhat", "perhaps", "maybe", "totally", "definitely", "certainly",
        "probably", "essentially", "virtually", "obviously", "clearly", "honestly",
        "seriously", "truly", "pretty", "stuff", "things", "anyway", "somehow",
    )
    private val phrases = Regex(
        "\\b(?:kind of|sort of|in order to|a bit|a little bit|the fact that|needless to say|" +
            "at the end of the day|for all intents and purposes|it goes without saying)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val notAdverbs = setOf(
        "only", "family", "early", "reply", "apply", "supply", "july", "italy", "ugly", "holy",
        "daily", "weekly", "monthly", "yearly", "likely", "unlikely", "ally", "belly", "bully",
        "jelly", "rely", "lonely", "lovely", "friendly", "silly", "assembly", "anomaly",
        "monopoly", "butterfly", "multiply", "comply", "imply", "curly", "elderly", "costly",
        "deadly", "lively", "orderly", "timely", "homely", "ghostly", "melancholy", "rally",
        "tally", "folly", "jolly", "bubbly", "wobbly", "smelly", "chilly", "hilly", "woolly",
        "gully", "dolly", "trolley", "firefly", "worldly", "cowardly", "scholarly", "heavenly",
    )
    private val be = setOf("is", "are", "was", "were", "be", "been", "being", "am")
    private val irregular = setOf(
        "written", "taken", "given", "made", "done", "known", "seen", "built", "sent", "held",
        "kept", "found", "told", "shown", "left", "lost", "paid", "said", "sold", "thought",
        "brought", "bought", "caught", "chosen", "driven", "eaten", "forgotten", "hidden",
        "broken", "spoken", "stolen", "thrown", "worn", "begun", "drawn", "grown", "heard",
        "led", "meant", "put", "read", "set", "shot", "spent", "taught", "understood", "won",
        "beaten", "bitten", "blown", "born", "forgiven", "frozen", "ridden", "risen", "shaken",
        "sung", "sworn", "torn", "woken", "withdrawn", "cut", "hit", "hurt", "laid", "felt",
    )
    private val notParticiples = setOf(
        "need", "speed", "indeed", "hundred", "sacred", "naked", "wicked", "seed", "feed",
        "weed", "breed", "bleed", "greed", "creed", "proceed", "succeed", "exceed", "shed",
    )

    private fun isAdverb(w: String) = w.length > 4 && w.endsWith("ly") && w !in notAdverbs
    private fun isParticiple(w: String) =
        w in irregular || (w.length > 4 && w.endsWith("ed") && w !in notParticiples)

    /** True if only spaces separate two words (no punctuation or line break). */
    private fun adjacent(text: String, from: Int, to: Int): Boolean {
        for (i in from until to) if (text[i] != ' ' && text[i] != '\t') return false
        return true
    }

    /** Issues ordered for drawing: LONG sentence washes first, word marks on top. */
    fun check(text: String): List<Issue> {
        val out = ArrayList<Issue>()
        for (s in Text.sentences(text)) {
            if (Text.words(text.substring(s.start, s.end)) > LONG_WORDS) out.add(Issue(s.start, s.end, IssueKind.LONG))
        }
        val tokens = word.findAll(text).toList()
        for ((i, m) in tokens.withIndex()) {
            val w = m.value.lowercase()
            val start = m.range.first
            val end = m.range.last + 1
            when {
                w in fillers -> out.add(Issue(start, end, IssueKind.FILLER))
                isAdverb(w) -> out.add(Issue(start, end, IssueKind.ADVERB))
            }
            if (w in be) {
                var j = i + 1
                var from = end
                if (j < tokens.size && isAdverb(tokens[j].value.lowercase()) && adjacent(text, from, tokens[j].range.first)) {
                    from = tokens[j].range.last + 1
                    j++
                }
                if (j < tokens.size && isParticiple(tokens[j].value.lowercase()) && adjacent(text, from, tokens[j].range.first)) {
                    out.add(Issue(start, tokens[j].range.last + 1, IssueKind.PASSIVE))
                }
            }
        }
        for (m in phrases.findAll(text)) out.add(Issue(m.range.first, m.range.last + 1, IssueKind.FILLER))
        return out.sortedWith(compareBy({ it.kind != IssueKind.LONG }, { it.start }))
    }

    fun count(issues: List<Issue>, kind: IssueKind): Int = issues.count { it.kind == kind }
}
