package com.callanswer.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.util.Log
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.audio.AgentAudioPlayerImpl
import com.callanswer.app.auth.AuthState
import com.callanswer.app.cloudtts.PollyCacheSynthesizer
import com.callanswer.app.audio.EchoGate
import com.callanswer.core.model.DEFAULT_AGENT_NAME
import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Plays a short sample in the chosen voice and language from the settings screen (9.15).
 * Uses the same chain as a call (cached Polly audio when signed in, then Piper, then platform TTS), with its own
 * speaker player.
 */
class VoiceSamplePlayer(context: Context) {
    private val app = context.applicationContext
    private val pollyCache = (app as CallAnswerApp).pollyCache
    private val sources: Map<SampleSource, SpeechSynthesizerPort> = mapOf(
        SampleSource.POLLY_CACHE to PollyCacheSynthesizer(pollyCache),
        SampleSource.PIPER to SherpaPiperSynthesizer(DirectoryPiperVoiceLocator(File(app.filesDir, "tts-voices"))),
        SampleSource.ANDROID_TTS to AndroidTtsSynthesizer(app),
    )
    // Not in a call: voice-communication routing can go to the earpiece at near-zero volume on Samsung.
    private val player = AgentAudioPlayerImpl(
        EchoGate(),
        usages = listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ASSISTANT),
    )

    /** Why the last [speak] returned false. */
    enum class Failure { NONE, HINDI_VOICE_DOWNLOADING, NO_VOICE, PLAYBACK }

    @Volatile
    var lastFailure: Failure = Failure.NONE
        private set

    /** Returns true when the sample played; false when no voice could synthesize it. */
    suspend fun play(language: Language, gender: VoiceGender, agentName: String = DEFAULT_AGENT_NAME): Boolean =
        speak(sampleText(language, agentName), language, gender)

    /** Speaks arbitrary [text], e.g. the full Delivery_Directions sample (9.21). Same result contract as [play]. */
    suspend fun speak(text: String, language: Language, gender: VoiceGender): Boolean {
        lastFailure = Failure.NONE
        val signedIn = (app as CallAnswerApp).authRepository.state.value is AuthState.SignedIn
        val order = SampleSourcePlan.order(signedIn, gender, pollyCache.has(text.trim(), language))
        Log.i(TAG, "Sample sources lang=$language gender=$gender signedIn=$signedIn textLength=${text.length} order=$order")
        var failure: Throwable? = null
        var result: SynthResult? = null
        for (source in order) {
            Log.i(TAG, "Sample source chosen: $source")
            try {
                Log.i(TAG, "Sample synth start source=$source lang=$language")
                result = sources.getValue(source).synthesize(text, language, gender).also {
                    Log.i(TAG, "Sample synth done source=$source voice=${it.voice.id} substituted=${it.substituted} samples=${it.pcm.size} rate=${it.sampleRate}")
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Sample synth fail source=$source lang=$language: ${t.message}")
                failure = t
            }
        }
        if (result == null) {
            lastFailure = if (language == Language.HI && failure is TtsVoiceUnavailableException) {
                Failure.HINDI_VOICE_DOWNLOADING
            } else {
                Failure.NO_VOICE
            }
            return false
        }
        return try {
            Log.i(TAG, "Sample playback start samples=${result.pcm.size} rate=${result.sampleRate} lang=$language")
            player.play(result.pcm, result.sampleRate, "voice-sample")
            Log.i(TAG, "Sample playback done lang=$language")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Sample playback fail lang=$language: ${t.javaClass.simpleName}: ${t.message}", t)
            lastFailure = Failure.PLAYBACK
            false
        }
    }

    fun stop() = player.stop()

    fun release() {
        player.stop()
        sources.values.forEach { runCatching { it.release() } }
    }

    companion object {
        private const val TAG = "CallAnswer"
        fun sampleText(language: Language, agentName: String = DEFAULT_AGENT_NAME): String = when (language) {
            Language.EN -> "Hello, this is $agentName."
            Language.HI -> "नमस्ते, मैं $agentName बोल रहा हूँ।"
        }
    }
}
