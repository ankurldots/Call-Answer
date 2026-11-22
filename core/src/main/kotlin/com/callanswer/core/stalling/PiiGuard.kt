package com.callanswer.core.stalling

import com.callanswer.core.model.GateConfig
import com.callanswer.core.model.Language
import java.text.Normalizer

/**
 * Keeps real User data out of prompts and replies (Requirements 5.4, 5.7, 10.7, 14.8).
 *
 * Matching is normalized (NFKC, case-folded, punctuation ignored), token-level for words
 * (so "Sam" does not match "Samsung") and digit-insensitive for numbers (formatting such as
 * "+91 98765-43210" versus "9876543210" does not matter).
 */
class PiiGuard(protectedValues: Set<String>) {

    private val tokenSequences: List<List<String>> = protectedValues
        .map { tokenize(it).map { t -> t.norm } }
        // A single one-character token (e.g. gate number "1") would match ordinary text; skip it.
        .filter { it.isNotEmpty() && !(it.size == 1 && it[0].length < 2) }
        .distinct()

    private val digitStrings: List<String> = protectedValues
        .map { digitsOf(it) }
        .filter { it.length >= MIN_PROTECTED_DIGITS }
        .distinct()

    /** True if [text] contains any protected value or a sensitive pattern. */
    fun containsProtected(text: String): Boolean {
        if (containsProtectedValue(text)) return true
        return PATTERNS.any { it.containsMatchIn(text) } || hasLongDigitRun(text)
    }

    /** Replaces protected values and sensitive patterns in [callerText] with a placeholder. */
    fun sanitizePrompt(callerText: String): String {
        val ranges = mutableListOf<IntRange>()
        PATTERNS.forEach { re -> re.findAll(callerText).forEach { ranges += it.range } }
        DIGIT_GROUP.findAll(callerText).forEach { m ->
            val d = digitsOf(m.value)
            if (d.length >= MIN_RUN || digitStrings.any { d.contains(it) }) ranges += m.range
        }
        val tokens = tokenize(callerText)
        for (seq in tokenSequences) {
            var i = 0
            while (i + seq.size <= tokens.size) {
                if (seq.indices.all { tokens[i + it].norm == seq[it] }) {
                    ranges += tokens[i].start until tokens[i + seq.size - 1].end
                    i += seq.size
                } else {
                    i++
                }
            }
        }
        if (ranges.isEmpty()) return callerText
        val merged = merge(ranges)
        val sb = StringBuilder()
        var pos = 0
        for (r in merged) {
            sb.append(callerText, pos, r.first).append(PLACEHOLDER)
            pos = r.last + 1
        }
        sb.append(callerText, pos, callerText.length)
        return sb.toString()
    }

    /**
     * A reply is acceptable when it is non-blank, has no protected value or sensitive pattern,
     * contains exactly one question and (when [language] is given) is written in that language.
     */
    fun acceptReply(text: String, language: Language? = null): Boolean {
        if (text.isBlank()) return false
        if (containsProtected(text)) return false
        if (text.count { it in QUESTION_MARKS } != 1) return false
        return language == null || matchesLanguage(text, language)
    }

    private fun containsProtectedValue(text: String): Boolean {
        val tokens = tokenize(text).map { it.norm }
        for (seq in tokenSequences) {
            var i = 0
            while (i + seq.size <= tokens.size) {
                if (seq.indices.all { tokens[i + it] == seq[it] }) return true
                i++
            }
        }
        if (digitStrings.isNotEmpty()) {
            for (m in DIGIT_GROUP.findAll(text)) {
                val d = digitsOf(m.value)
                if (digitStrings.any { d.contains(it) }) return true
            }
        }
        return false
    }

    private fun hasLongDigitRun(text: String): Boolean =
        DIGIT_GROUP.findAll(text).any { digitsOf(it.value).length >= MIN_RUN }

    private fun matchesLanguage(text: String, language: Language): Boolean {
        val letters = text.filter { Character.isLetter(it) }
        if (letters.isEmpty()) return false
        val deva = letters.count { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.DEVANAGARI }
        val share = deva.toDouble() / letters.length
        return when (language) {
            Language.HI -> share >= 0.5
            Language.EN -> share < 0.2
        }
    }

    private class Token(val norm: String, val start: Int, val end: Int)

    companion object {
        const val PLACEHOLDER = "[removed]"
        private const val MIN_RUN = 6
        private const val MIN_PROTECTED_DIGITS = 3
        private val QUESTION_MARKS = setOf('?', '？', '؟')

        private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")
        private val DIGIT_GROUP = Regex("\\p{Nd}+(?:[ \\-.]?\\p{Nd}+)*")
        private val PATTERNS: List<Regex> = listOf(
            Regex("[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}.\\-]+\\.[\\p{L}]{2,}"),            // email
            Regex("(?i)\\b[A-Z]{5}\\d{4}[A-Z]\\b"),                                      // PAN
            Regex("(?i)\\b[A-Z]{4}0[A-Z0-9]{6}\\b"),                                     // IFSC
        )

        /** Builds a guard from the configured values (Requirement 11.11, 14.9). */
        fun of(
            protectedTerms: List<String>,
            mapLocation: String?,
            transferTarget: String?,
            ownNumber: String?,
            gates: GateConfig?,
        ): PiiGuard {
            val values = mutableSetOf<String>()
            values += protectedTerms
            listOf(mapLocation, transferTarget, ownNumber).forEach { if (it != null) values += it }
            gates?.let { g ->
                for (gate in listOf(g.gate1, g.gate3)) {
                    values += gate.number; values += gate.location; values += gate.landmark
                }
            }
            return PiiGuard(values.filter { it.isNotBlank() }.toSet())
        }

        private fun tokenize(s: String): List<Token> {
            val folded = Normalizer.normalize(s, Normalizer.Form.NFKC)
            // NFKC can change length; positions are only meaningful when the length is unchanged.
            val src = if (folded.length == s.length) folded else s
            return WORD.findAll(src).map {
                Token(foldDigits(it.value).lowercase(), it.range.first, it.range.last + 1)
            }.toList()
        }

        private fun foldDigits(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(if (Character.isDigit(c)) ('0' + Character.digit(c, 10)) else c)
            return sb.toString()
        }

        private fun digitsOf(s: String): String =
            foldDigits(Normalizer.normalize(s, Normalizer.Form.NFKC)).filter { it in '0'..'9' }

        private fun merge(ranges: List<IntRange>): List<IntRange> {
            val sorted = ranges.sortedBy { it.first }
            val out = mutableListOf<IntRange>()
            for (r in sorted) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last + 1) {
                    out[out.size - 1] = last.first..maxOf(last.last, r.last)
                } else {
                    out += r
                }
            }
            return out
        }
    }
}
