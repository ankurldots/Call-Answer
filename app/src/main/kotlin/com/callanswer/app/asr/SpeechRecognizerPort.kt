package com.callanswer.app.asr

import com.callanswer.app.audio.SpeechSegment
import com.callanswer.core.model.Language

/** Result of recognizing one utterance. An empty [text] means nothing was recognized (or the 3 s limit passed). */
data class AsrResult(val text: String, val languageTag: Language?, val elapsedMs: Long)

/** Speech-to-text for one Caller utterance. [release] frees the models at the end of the call. */
interface SpeechRecognizerPort {
    suspend fun transcribe(segment: SpeechSegment, languageHint: Language?): AsrResult
    fun release()
}

/** Converts PCM16 to floats in -1..1. */
internal fun ShortArray.toFloatPcm(): FloatArray = FloatArray(size) { this[it] / 32768f }
