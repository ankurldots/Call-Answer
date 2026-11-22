package com.callanswer.app.tts

import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.voice.VoiceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow

/**
 * PCM (16-bit mono) plus the voice that produced it. [substituted] is true when the voice does not
 * have the requested gender (Requirements 7.12, 7.13); callers log it.
 */
class SynthResult(
    val pcm: ShortArray,
    val sampleRate: Int,
    val voice: VoiceInfo,
    val substituted: Boolean = false,
)

interface SpeechSynthesizerPort {
    /** Synthesizes [text] in [language] with a voice of [gender]. Mixed-script text is split per script. */
    suspend fun synthesize(text: String, language: Language, gender: VoiceGender): SynthResult

    /** Frees engines and models. Called at call end. */
    fun release()
}

/**
 * Sentence-by-sentence synthesis: the first sentence is produced first, and each later sentence is
 * synthesized while the collector is still playing the previous one (one sentence of look-ahead).
 */
fun SpeechSynthesizerPort.synthesizeSentences(
    text: String,
    language: Language,
    gender: VoiceGender,
): Flow<SynthResult> = flow {
    for (sentence in SentenceSplitter.split(text)) emit(synthesize(sentence, language, gender))
}.buffer(1)

/** Splits text into sentences at `. ! ? ।` and newlines, keeping the terminator with its sentence. */
object SentenceSplitter {
    private val terminators = setOf('.', '!', '?', '\u0964', '\n')

    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for ((i, c) in text.withIndex()) {
            cur.append(c)
            val boundary = c in terminators && (i + 1 == text.length || text[i + 1].isWhitespace()) &&
                !(c == '.' && i > 0 && text[i - 1].isDigit() && i + 1 < text.length && text[i + 1].isDigit())
            if (boundary) {
                cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
                cur.setLength(0)
            }
        }
        cur.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add)
        return out
    }
}
