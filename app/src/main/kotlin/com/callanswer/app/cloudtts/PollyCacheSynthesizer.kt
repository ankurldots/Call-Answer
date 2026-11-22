package com.callanswer.app.cloudtts
import com.callanswer.app.tts.SpeechSynthesizerPort
import com.callanswer.app.tts.SynthResult
import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.voice.VoiceInfo
/**
 * First link of the voice chain: plays Polly audio already on the device. It never touches the network, so a
 * call works offline. A miss, or a request for a male voice (Kajal is female), throws so
 * [com.callanswer.app.tts.FallbackSynthesizer] moves to Piper and then the platform TTS.
 */
class PollyCacheSynthesizer(private val cache: PollyAudioCache) : SpeechSynthesizerPort {
    override suspend fun synthesize(text: String, language: Language, gender: VoiceGender): SynthResult {
        if (gender != VoiceGender.FEMALE) throw NoSuchElementException("no polly voice for gender")
        val pcm = cache.get(text.trim(), language) ?: throw NoSuchElementException("not cached")
        return SynthResult(pcm, PollyClient.SAMPLE_RATE, VoiceInfo("polly-kajal", language, VoiceGender.FEMALE, false))
    }
    override fun release() = Unit
}
