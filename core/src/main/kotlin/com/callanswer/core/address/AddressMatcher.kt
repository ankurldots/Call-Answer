package com.callanswer.core.address

import com.callanswer.core.intent.TextNormalizer

/**
 * Checks an address spoken by a delivery Caller against the configured address. The configured address is
 * only compared, never spoken. Speech recognition is loose, so the check is by words: ignoring filler words,
 * most of what the Caller said must appear in the configured address, and at least two words must match
 * (or one, when the configured address has a single significant word).
 */
object AddressMatcher {
    private const val MIN_SHARE = 0.6
    private const val MIN_MATCHED_WORDS = 2

    private val FILLER = setOf(
        "sir", "madam", "maam", "ma", "am", "the", "is", "are", "it", "its", "at", "to", "of", "in", "on", "for", "and",
        "address", "adress", "addres", "addresses", "delivery", "deliver", "parcel", "package", "order", "your", "my",
        "this", "that", "yes", "ok", "okay", "please", "hello", "hi", "hai", "hain", "ka", "ki", "ke", "me", "mein",
        "pe", "par", "ye", "yeh", "ji", "aap", "aapka", "apka", "kya", "na", "se", "no", "number", "near", "phone",
        "एड्रेस", "ऐड्रेस", "पता", "पते", "है", "हैं", "का", "की", "के", "में", "पर", "यह", "ये", "जी", "सर", "आपका",
    )

    /**
     * True when [spoken] is a believable statement of [configured] (English) or [configuredHi] (the Hindi form).
     * A blank input never matches. Words are compared by a sound key that ignores vowels, so "Green" and
     * "ग्रीन" are the same word, and different spellings of one Hindi word also agree.
     */
    fun matches(configured: String?, spoken: String, configuredHi: String? = null): Boolean {
        val have = significantWords(configured.orEmpty()) + significantWords(configuredHi.orEmpty())
        val said = significantWords(spoken)
        if (have.isEmpty() || said.isEmpty()) return false
        val matched = said.count { it in have }
        val needed = minOf(MIN_MATCHED_WORDS, have.size)
        return matched >= needed && matched.toDouble() / said.size >= MIN_SHARE
    }

    /** True when [spoken] has at least one word that could be part of an address (not only filler). */
    fun hasAddressWords(spoken: String): Boolean = significantWords(spoken).isNotEmpty()

    private fun significantWords(text: String): Set<String> =
        TextNormalizer.normalize(text).split(' ').filter { it.isNotEmpty() && it !in FILLER }
            .map { soundKey(it) }.toSet()

    private val DEVANAGARI_KEY = mapOf(
        'क' to "k", 'ख' to "k", 'ग' to "g", 'घ' to "g", 'च' to "C", 'छ' to "C", 'ज' to "j", 'झ' to "j",
        'ट' to "t", 'ठ' to "t", 'त' to "t", 'थ' to "t", 'ड' to "d", 'ढ' to "d", 'द' to "d", 'ध' to "d",
        'न' to "n", 'ण' to "n", 'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "b", 'म' to "m", 'र' to "r",
        'ल' to "l", 'व' to "v", 'श' to "S", 'ष' to "S", 'स' to "s",
    )

    /** A key made of the consonant sounds of [word], in Roman or Devanagari. Digits keep their value. */
    internal fun soundKey(word: String): String {
        val sb = StringBuilder()
        var roman = word.replace("ksh", "kS").replace("sh", "S").replace("ch", "C")
            .replace(Regex("(?:th|dh|kh|gh|bh|jh)")) { it.value.substring(0, 1) }
            .replace("ph", "f")
            .replace(Regex("c(?=[eiy])"), "s")
        roman = roman.replace('c', 'k').replace('q', 'k').replace('w', 'v').replace('z', 'j').replace("x", "ks")
        for (ch in roman) {
            val mapped = when {
                ch in '०'..'९' -> ('0' + (ch - '०')).toString()
                ch in DEVANAGARI_KEY -> DEVANAGARI_KEY.getValue(ch)
                ch in "bdfgjklmnprstvCSk" || ch.isDigit() -> ch.toString()
                else -> ""
            }
            if (mapped.isNotEmpty() && !sb.endsWith(mapped)) sb.append(mapped)
        }
        return if (sb.isEmpty()) word else sb.toString()
    }
}
