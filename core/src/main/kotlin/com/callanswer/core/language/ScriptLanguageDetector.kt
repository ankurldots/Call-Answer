package com.callanswer.core.language

import com.callanswer.core.model.Language

/** Identifies the language of an utterance; null means it cannot be identified. */
interface LanguageDetector {
    fun detect(utterance: String): Language?
}

/**
 * Text-based detector. A token is Hindi if it contains a Devanagari letter or is a Roman token in the
 * Hinglish lexicon; English if it is another Roman token containing a letter. Digit-only and punctuation
 * tokens are not counted. A language needs more than 50% of counted tokens; on an exact tie the language
 * of the first counted token wins; null when no token is counted.
 */
class ScriptLanguageDetector(hinglishLexicon: Set<String>) : LanguageDetector {
    private val lexicon: Set<String> = hinglishLexicon.mapTo(HashSet()) { it.lowercase() }

    override fun detect(utterance: String): Language? {
        var hindi = 0
        var english = 0
        var first: Language? = null
        for (token in tokenize(utterance)) {
            val lang = classify(token) ?: continue
            if (first == null) first = lang
            if (lang == Language.HI) hindi++ else english++
        }
        return when {
            hindi == 0 && english == 0 -> null
            hindi > english -> Language.HI
            english > hindi -> Language.EN
            else -> first
        }
    }

    private fun classify(token: String): Language? {
        var hasLetter = false
        var hasDevanagariLetter = false
        for (ch in token) {
            if (Character.isLetter(ch)) {
                hasLetter = true
                if (ch.code in DEVANAGARI) hasDevanagariLetter = true
            }
        }
        return when {
            hasDevanagariLetter -> Language.HI
            !hasLetter -> null
            token.lowercase() in lexicon -> Language.HI
            else -> Language.EN
        }
    }

    private fun tokenize(text: String): List<String> {
        val tokens = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                val t = sb.toString().trim('\'', '\u2019')
                if (t.isNotEmpty()) tokens.add(t)
                sb.setLength(0)
            }
        }
        for (ch in text) {
            if (Character.isLetterOrDigit(ch) || isMark(ch) || ch == '\'' || ch == '\u2019') sb.append(ch) else flush()
        }
        flush()
        return tokens
    }

    private fun isMark(ch: Char): Boolean = when (Character.getType(ch).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    companion object {
        private val DEVANAGARI = 0x0900..0x097F
        private const val RESOURCE = "/language/hinglish_lexicon.txt"

        /** Loads the bundled Hinglish lexicon (one lowercase token per line, `#` comments). */
        fun loadBundledLexicon(): Set<String> {
            val stream = ScriptLanguageDetector::class.java.getResourceAsStream(RESOURCE)
                ?: error("Missing resource $RESOURCE")
            return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            }
        }

        fun withBundledLexicon(): ScriptLanguageDetector = ScriptLanguageDetector(loadBundledLexicon())
    }
}

object ResponseLanguageResolver {
    fun resolve(detected: Language?, lastIdentified: Language?): Language =
        detected ?: lastIdentified ?: Language.EN
}
