package com.callanswer.app.tts

import android.content.Context
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.audio.AgentAudioPlayerImpl
import com.callanswer.app.cloudtts.PollyCacheSynthesizer
import com.callanswer.app.audio.EchoGate
import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Plays a short sample in the chosen voice and language from the settings screen (9.15).
 * Uses the same synthesizer chain as a call (Piper, then platform TTS) with its own player.
 */
class VoiceSamplePlayer(context: Context) {
    private val app = context.applicationContext
    private val synthesizer: SpeechSynthesizerPort = FallbackSynthesizer(
        PollyCacheSynthesizer((app as CallAnswerApp).pollyCache),
        FallbackSynthesizer(
            SherpaPiperSynthesizer(DirectoryPiperVoiceLocator(File(app.filesDir, "tts-voices"))),
            AndroidTtsSynthesizer(app),
        ),
    )
    private val player = AgentAudioPlayerImpl(EchoGate())

    /** Returns true when the sample played; false when no voice could synthesize it. */
    suspend fun play(language: Language, gender: VoiceGender): Boolean =
        speak(sampleText(language), language, gender)

    /** Speaks arbitrary [text], e.g. the full Delivery_Directions sample (9.21). Same result contract as [play]. */
    suspend fun speak(text: String, language: Language, gender: VoiceGender): Boolean = try {
        val result = synthesizer.synthesize(text, language, gender)
        player.play(result.pcm, result.sampleRate, "voice-sample")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        false
    }

    fun stop() = player.stop()

    fun release() {
        player.stop()
        synthesizer.release()
    }

    companion object {
        fun sampleText(language: Language): String = when (language) {
            Language.EN -> "Hello, this is the assistant answering the call."
            Language.HI -> "नमस्ते, मैं कॉल का जवाब देने वाला सहायक हूँ।"
        }
    }
}
