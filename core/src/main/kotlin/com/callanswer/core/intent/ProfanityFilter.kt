package com.callanswer.core.intent
import java.text.Normalizer
/**
 * Detects abusive language in a Caller utterance: English, Roman Hinglish and Devanagari.
 *
 * Only strong abuse is listed. Words with an everyday meaning a delivery Caller may use (for example `kutta`
 * "dog", `saala` "brother-in-law", `ullu`, `damn`, `shit`) are left out on purpose, because a false match hangs up
 * on a genuine Caller. Patterns are matched on [TextNormalizer] output and are word-bounded, so `gandhi` does
 * not match `gand`. Words the speech recognizer masks with asterisks (`f***`) count as abuse.
 */
object ProfanityFilter {
    private const val L = "\\p{L}\\p{M}\\p{N}"
    /** Inflection suffix, e.g. `fucking`, `chutiye`. */
    private const val S = "[\\p{L}\\p{M}]*"
    private val patterns: List<Regex> = listOf(
        // English
        "fu+c?k$S|f+u+k+$S|motherfuck$S|mofo|fck$S|fuk$S",
        "bitch$S|bastard$S|asshole$S|arsehole$S|cunt$S|dickhead$S|whore$S|slut$S|wanker$S|prick",
        "son of a bitch",
        // Roman Hinglish (spelling variants as speech recognizers write them)
        "madarc?h?od$S|maderc?h?od$S|maadarchod$S|madarjaat",
        "behen ?c?h?od$S|bhen ?c?h?od$S|bh?enc?h?od$S|bhanchod$S|behan ?chod$S",
        "bhosdi$S|bhosadi$S|bhosri$S|bhosda$S",
        "chutiy$S|chootiy$S|chutia$S|choot|chut",
        "gaa?ndu|gaa?nd mar$S",
        "randi$S|haraa?mi$S|haraa?mzaa?d$S|haraa?mkhor$S",
        "laa?u?da|lavda|lawda|lodu|lund",
        "kutiya|kuttiya|bhadwa$S|bhadve|bhadwe",
        "teri (?:maa|ma|behen|bhen) ki",
        // Devanagari (written with nukta; it is removed below, as the normalizer does)
        "मादरचोद$S|मादर चोद$S|मादरजात",
        "बहनचोद$S|बहन चोद$S|बहेनचोद$S|भेनचोद$S|भैनचोद$S",
        "भोसड़ी$S|भोसडी$S|भोसड़ा$S|भोसड़$S",
        "चूतिय$S|चुतिय$S|चूत",
        "गांडू|गांड|गाँड$S|गाण्डू",
        "रंडी$S|हरामी$S|हरामज़ाद$S|हरामखोर$S",
        "लौड़ा|लौड़े|लोड़ा|लंड",
        "कुतिया|भड़वा$S|भड़वे",
        "तेरी (?:मां|माँ|मा|बहन|बहेन) की",
    ).map {
        val p = Normalizer.normalize(it, Normalizer.Form.NFKC).replace("\u093C", "")
        Regex("(?<![$L])(?:$p)(?![$L])")
    }
    /** A recognizer-masked word such as `f***` or `b*tch` in the raw text. */
    private val masked = Regex("(?<![\\p{L}])[\\p{L}]+\\*{2,}[\\p{L}]*|(?<![\\p{L}])[\\p{L}]\\*+[\\p{L}]+")
    fun isAbusive(utterance: String): Boolean {
        if (masked.containsMatchIn(utterance)) return true
        val text = TextNormalizer.normalize(utterance)
        return patterns.any { it.containsMatchIn(text) }
    }
}
