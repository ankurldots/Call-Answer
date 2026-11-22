package com.callanswer.spike

import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max

/** Pure helpers for S7: text normalization, WER, percentiles and the draft intent phrase table. */
object S7Metrics {

    /** NFKC, lower case, drop Devanagari nukta, strip punctuation, collapse spaces. */
    fun normalize(text: String): String {
        val n = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase().replace("\u093C", "")
        return n.replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }

    fun words(text: String): List<String> = normalize(text).let { if (it.isEmpty()) emptyList() else it.split(" ") }

    /** Word-level Levenshtein distance. Returns the number of errors (substitutions + deletions + insertions). */
    fun wordErrors(ref: List<String>, hyp: List<String>): Int {
        var prev = IntArray(hyp.size + 1) { it }
        for (i in 1..ref.size) {
            val cur = IntArray(hyp.size + 1)
            cur[0] = i
            for (j in 1..hyp.size) {
                val sub = prev[j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                cur[j] = minOf(sub, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev[hyp.size]
    }

    /** Nearest-rank percentile (p in 0..100). Returns NaN for an empty list. */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val rank = max(1, ceil(p / 100.0 * sorted.size).toInt())
        return sorted[rank - 1]
    }

    // Draft phrase table (Devanagari, Roman Hinglish, English). Final table is built in task 6.1 using this output.
    private val table: Map<String, List<String>> = linkedMapOf(
        "DELIVERY" to listOf(
            "delivery", "डिलीवरी", "parcel", "पार्सल", "courier", "कूरियर", "कुरियर", "address", "एड्रेस", "पता", "order", "ऑर्डर", "package",
        ),
        "DOOR_NOT_OPENED" to listOf(
            "outside", "door", "gate", "darwaza", "darwaja", "दरवाजा", "दरवाज़ा", "गेट", "बाहर खड़ा", "bahar khada",
        ),
        "SOLICITATION" to listOf(
            "loan", "लोन", "offer", "ऑफर", "otp", "ओटीपी", "bank", "बैंक", "kyc", "credit card", "क्रेडिट", "plot", "प्लॉट",
            "flat", "investment", "इन्वेस्टमेंट", "insurance", "बीमा", "scheme", "स्कीम", "lucky draw", "इनाम", "gift",
            "free", "congratulations", "बधाई", "prize",
        ),
    )

    private val patterns: List<Pair<String, Regex>> = table.flatMap { (intent, phrases) ->
        phrases.map { raw ->
            val p = normalize(raw)
            val ascii = p.all { it.code < 128 }
            intent to Regex(if (ascii) "\\b${Regex.escape(p)}\\b" else Regex.escape(p))
        }
    }

    /** Earliest match start wins; ties go to the longer match; no match gives UNKNOWN. */
    fun classify(transcript: String): String {
        val t = normalize(transcript)
        var best: Triple<String, Int, Int>? = null // intent, start, length
        for ((intent, re) in patterns) {
            val m = re.find(t) ?: continue
            val b = best
            if (b == null || m.range.first < b.second || (m.range.first == b.second && m.value.length > b.third)) {
                best = Triple(intent, m.range.first, m.value.length)
            }
        }
        return best?.first ?: "UNKNOWN"
    }
}
