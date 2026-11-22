package com.callanswer.core.intent

import java.text.Normalizer
import java.util.Locale

/**
 * Normalizes utterances before matching: NFKC, lower case, Devanagari nukta removal
 * (for example `ज़` to `ज`), punctuation strip and space collapse.
 * Match positions are computed on the normalized text.
 */
object TextNormalizer {
    private const val NUKTA = '\u093C'
    private val apostrophes = setOf('\'', '\u2019', '\u2018', '`')

    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when {
                ch == NUKTA -> Unit
                ch in apostrophes -> Unit
                Character.isLetterOrDigit(ch) -> sb.append(ch)
                isCombiningMark(ch) -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    private fun isCombiningMark(ch: Char): Boolean = when (Character.getType(ch).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }
}
