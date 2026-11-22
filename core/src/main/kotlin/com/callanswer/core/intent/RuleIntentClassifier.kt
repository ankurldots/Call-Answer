package com.callanswer.core.intent

import com.callanswer.core.model.IntentCategory

data class IntentResult(val category: IntentCategory, val gateQuery: Boolean, val matchStart: Int?)

interface IntentClassifier { fun classify(utterance: String): IntentResult }

/**
 * Earliest match start wins. Ties go to the longer match, then to the order
 * DELIVERY, DOOR_NOT_OPENED, SOLICITATION. No match gives UNKNOWN.
 * `gateQuery` comes from the Gate_Query table only and never affects the category.
 */
class RuleIntentClassifier(private val phrases: PhraseTables = PhraseTables.DEFAULT) : IntentClassifier {

    private val order = listOf(IntentCategory.DELIVERY, IntentCategory.DOOR_NOT_OPENED, IntentCategory.SOLICITATION)

    override fun classify(utterance: String): IntentResult {
        val text = TextNormalizer.normalize(utterance)
        var best: IntentCategory? = null
        var bestStart = Int.MAX_VALUE
        var bestLen = -1
        for (category in order) {
            val m = firstMatch(phrases.forCategory(category), text) ?: continue
            val start = m.first
            val len = m.second
            if (start < bestStart || (start == bestStart && len > bestLen)) {
                best = category
                bestStart = start
                bestLen = len
            }
        }
        val gate = phrases.gateQuery.any { it.containsMatchIn(text) }
        return if (best == null) IntentResult(IntentCategory.UNKNOWN, gate, null)
        else IntentResult(best, gate, bestStart)
    }

    /** Start and length of the match that starts first in [text] (longest on equal starts). */
    private fun firstMatch(table: List<Regex>, text: String): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (r in table) {
            val m = r.find(text) ?: continue
            val start = m.range.first
            val len = m.value.length
            val b = best
            if (b == null || start < b.first || (start == b.first && len > b.second)) best = start to len
        }
        return best
    }
}
