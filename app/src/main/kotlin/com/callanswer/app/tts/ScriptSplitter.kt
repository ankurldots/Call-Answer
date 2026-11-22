package com.callanswer.app.tts

import com.callanswer.core.model.Language

/** A maximal piece of text spoken in one language. */
data class ScriptRun(val language: Language, val text: String)

/**
 * Splits mixed-script text (for example a Hindi sentence with an English landmark) into runs.
 * Devanagari letters are [Language.HI], Latin letters are [Language.EN]. Digits, punctuation and
 * spaces join the run before them (or the first run when they lead). Text without any letter is one
 * run in [fallback]. Pure, so it runs in JVM tests.
 */
object ScriptSplitter {
    fun split(text: String, fallback: Language): List<ScriptRun> {
        if (text.isEmpty()) return emptyList()
        val runs = ArrayList<ScriptRun>()
        var current: Language? = null
        val buf = StringBuilder()
        for (c in text) {
            val lang = languageOf(c)
            if (lang != null && current != null && lang != current) {
                runs.add(ScriptRun(current, buf.toString()))
                buf.setLength(0)
            }
            if (lang != null) current = lang
            buf.append(c)
        }
        runs.add(ScriptRun(current ?: fallback, buf.toString()))
        return runs.filter { it.text.isNotBlank() }
    }

    private fun languageOf(c: Char): Language? = when {
        c in '\u0900'..'\u097F' && Character.isLetter(c) -> Language.HI
        // Combining vowel signs and virama inside Devanagari words are marks, not letters.
        c in '\u0900'..'\u097F' && Character.getType(c).let {
            it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt()
        } -> Language.HI
        c in 'a'..'z' || c in 'A'..'Z' || c in '\u00C0'..'\u024F' -> Language.EN
        else -> null
    }
}
