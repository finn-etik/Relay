package dev.field.lore

import kotlin.math.ln

/**
 * A small full-text index: Okapi BM25 over documents already cut into words
 * ([Words.tokens]). Built in one pass, immutable afterwards. It ranks the
 * note passages, and the passages of the few articles fetched for a question.
 */
class Bm25(docs: List<List<String>>) {
    data class Hit(val doc: Int, val score: Double, /** How many distinct query words the document contains. */ val matched: Int)

    val size = docs.size
    private val lengths = IntArray(size) { docs[it].size }
    private val average = if (size == 0) 0.0 else lengths.sum().toDouble() / size

    /** word -> (document, occurrences) pairs, flattened. */
    private val postings: Map<String, IntArray>

    init {
        val building = HashMap<String, ArrayList<Int>>()
        for ((d, words) in docs.withIndex()) {
            for ((word, count) in words.groupingBy { it }.eachCount()) {
                building.getOrPut(word) { ArrayList() }.apply { add(d); add(count) }
            }
        }
        postings = building.mapValues { it.value.toIntArray() }
    }

    fun search(query: List<String>, limit: Int): List<Hit> {
        if (size == 0) return emptyList()
        val scores = HashMap<Int, Double>()
        val matched = HashMap<Int, Int>()
        for (word in query.distinct()) {
            val list = postings[word] ?: continue
            val df = list.size / 2
            val idf = ln(1.0 + (size - df + 0.5) / (df + 0.5))
            for (i in list.indices step 2) {
                val d = list[i]
                val tf = list[i + 1].toDouble()
                val norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths[d] / average))
                scores[d] = (scores[d] ?: 0.0) + idf * norm
                matched[d] = (matched[d] ?: 0) + 1
            }
        }
        return scores.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Double>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { Hit(it.key, it.value, matched.getValue(it.key)) }
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
    }
}
