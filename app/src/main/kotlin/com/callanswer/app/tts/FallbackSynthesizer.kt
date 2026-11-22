package com.callanswer.app.tts

import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import kotlinx.coroutines.CancellationException

/** Uses [primary] and, when it throws, [secondary] (Piper first, then the platform TTS; Requirement 8.11). */
class FallbackSynthesizer(
    private val primary: SpeechSynthesizerPort,
    private val secondary: SpeechSynthesizerPort,
) : SpeechSynthesizerPort {
    override suspend fun synthesize(text: String, language: Language, gender: VoiceGender): SynthResult =
        try {
            primary.synthesize(text, language, gender)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            secondary.synthesize(text, language, gender)
        }

    override fun release() {
        runCatching { primary.release() }
        runCatching { secondary.release() }
    }
}
