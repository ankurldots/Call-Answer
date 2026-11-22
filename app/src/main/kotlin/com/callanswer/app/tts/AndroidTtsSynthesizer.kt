package com.callanswer.app.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.voice.VoiceChoice
import com.callanswer.core.voice.VoiceInfo
import com.callanswer.core.voice.VoiceSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Fallback synthesizer on the platform [TextToSpeech] engine. Audio goes to a temporary WAV file and
 * is returned as PCM, so [AgentAudioPlayer][com.callanswer.app.audio.AgentAudioPlayerImpl] still owns
 * playback. [VoiceSelector] picks the voice; a voice of another gender is reported through
 * [onSubstitution] and [SynthResult.substituted] (Requirements 7.12, 7.13).
 */
class AndroidTtsSynthesizer(
    private val context: Context,
    private val onSubstitution: (VoiceChoice, Language, VoiceGender) -> Unit = { _, _, _ -> },
) : SpeechSynthesizerPort {
    private var tts: TextToSpeech? = null

    private suspend fun engine(): TextToSpeech {
        tts?.let { return it }
        val created = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var holder: TextToSpeech? = null
            holder = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) cont.resume(holder) else { holder?.shutdown(); cont.resume(null) }
            }
            cont.invokeOnCancellation { holder?.shutdown() }
        } ?: throw IllegalStateException("Android TextToSpeech failed to initialise")
        tts = created
        return created
    }

    override suspend fun synthesize(text: String, language: Language, gender: VoiceGender): SynthResult {
        val engine = engine()
        val voices = engine.voices.orEmpty()
        val infos = voices.mapNotNull { toInfo(it) }
        val runs = ScriptSplitter.split(text, language)
        if (runs.isEmpty()) throw IllegalStateException("Nothing to synthesize")
        val parts = ArrayList<Pair<ShortArray, Int>>()
        var first: VoiceChoice? = null
        var anySubstituted = false
        for (run in runs) {
            val choice = VoiceSelector.select(gender, run.language, infos)
                ?: run {
                    Log.w(CA, "Android TTS has no voice for ${run.language} (installed: ${infos.size})")
                    throw TtsVoiceUnavailableException(run.language, "No Android TTS voice for ${run.language}")
                }
            if (choice.substituted) {
                anySubstituted = true
                Log.w(TAG, "Voice substituted for ${run.language}: wanted $gender, using ${choice.voice.id}")
                onSubstitution(choice, run.language, gender)
            }
            if (first == null) first = choice
            engine.voice = voices.first { it.name == choice.voice.id }
            Log.i(CA, "TTS synth start lang=${run.language} voice=${choice.voice.id} chars=${run.text.length}")
            val part = try {
                render(engine, run.text, run.language)
            } catch (e: TtsVoiceUnavailableException) {
                Log.w(CA, "TTS synth fail lang=${run.language}: ${e.message}")
                throw e
            }
            Log.i(CA, "TTS synth done lang=${run.language} samples=${part.first.size} rate=${part.second}")
            parts.add(part)
        }
        val rate = parts.first().second
        return SynthResult(PcmJoin.concat(parts, rate), rate, first!!.voice, anySubstituted)
    }

    private suspend fun render(engine: TextToSpeech, text: String, language: Language): Pair<ShortArray, Int> {
        val file = File(context.cacheDir, "tts-${UUID.randomUUID()}.wav")
        try {
            val id = UUID.randomUUID().toString()
            val ok = withTimeoutOrNull(SYNTH_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.w(CA, "TTS engine error code=$errorCode")
                            if (utteranceId == id && cont.isActive) cont.resume(false)
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            if (utteranceId == id && cont.isActive) cont.resume(false)
                        }
                    })
                    val r = engine.synthesizeToFile(text, null, file, id)
                    if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
                }
            }
            if (ok == null) throw TtsVoiceUnavailableException(language, "Android TTS timed out after ${SYNTH_TIMEOUT_MS} ms")
            if (!ok) throw TtsVoiceUnavailableException(language, "Android TTS failed to synthesize")
            return withContext(Dispatchers.IO) { readWav(file.readBytes()) }
        } finally {
            file.delete()
        }
    }

    override fun release() {
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "AndroidTts"
        private const val CA = "CallAnswer"
        private const val SYNTH_TIMEOUT_MS = 25_000L

        /** Maps a platform voice to a [VoiceInfo]; null for languages the Agent does not speak. */
        internal fun toInfo(v: Voice): VoiceInfo? {
            val lang = when (v.locale.language) {
                "hi" -> Language.HI
                "en" -> Language.EN
                else -> return null
            }
            val n = v.name.lowercase()
            val gender = when {
                "female" in n -> VoiceGender.FEMALE
                "male" in n -> VoiceGender.MALE
                else -> null
            }
            return VoiceInfo(v.name, lang, gender, bundled = !v.isNetworkConnectionRequired)
        }

        /** Parses a 16-bit PCM WAV (RIFF) file into mono samples and its sample rate. */
        internal fun readWav(bytes: ByteArray): Pair<ShortArray, Int> {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var rate = 22050
            var channels = 1
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val tag = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = bb.getInt(pos + 4)
                if (tag == "fmt ") {
                    channels = bb.getShort(pos + 10).toInt().coerceAtLeast(1)
                    rate = bb.getInt(pos + 12)
                } else if (tag == "data") {
                    val remaining = (bytes.size - pos - 8).toLong()
                    val declared = size.toLong().and(0xFFFFFFFFL)
                    // Streaming writers leave the size as 0 or 0xFFFFFFFF; use the rest of the file then.
                    val len = (if (declared == 0L || declared > remaining) remaining else declared).toInt()
                    val frames = len / 2 / channels
                    val out = ShortArray(frames)
                    for (i in 0 until frames) out[i] = bb.getShort(pos + 8 + i * 2 * channels)
                    return out to rate
                }
                if (size < 0) break
                pos += 8 + size + (size and 1)
            }
            throw IllegalStateException("WAV has no data chunk")
        }
    }
}

/** The platform TTS has no usable voice for [language] (missing, still downloading, or synthesis failed/timed out). */
class TtsVoiceUnavailableException(val language: Language, message: String) : IllegalStateException(message)
