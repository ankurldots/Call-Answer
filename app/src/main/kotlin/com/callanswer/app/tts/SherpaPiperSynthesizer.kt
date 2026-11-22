package com.callanswer.app.tts

import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.voice.VoiceInfo
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Finds the unpacked Piper voice folder (model `.onnx`, `tokens.txt`, `espeak-ng-data/`) for a voice. */
fun interface PiperVoiceLocator {
    fun dir(language: Language, gender: VoiceGender): File?
}

/** Looks for `<root>/piper-<hi|en>-<female|male>`. */
class DirectoryPiperVoiceLocator(private val root: File) : PiperVoiceLocator {
    override fun dir(language: Language, gender: VoiceGender): File? =
        File(root, "piper-${language.name.lowercase()}-${gender.name.lowercase()}").takeIf { it.isDirectory }
}

/** Thrown when no bundled Piper voice can serve a request, so the caller can fall back to Android TTS. */
class PiperUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Bundled Piper voices through sherpa-onnx (works offline, Requirement 8.11). Only the two voices
 * (Hindi and English) of the call's frozen gender are loaded, on first use; they are released by
 * [release] at call end. Mixed-script text is split by [ScriptSplitter] and every run is spoken by
 * the voice of its script in the same gender (7.8, 7.10, 7.11).
 */
class SherpaPiperSynthesizer(private val locator: PiperVoiceLocator) : SpeechSynthesizerPort {
    private val lock = Mutex()
    private var loadedGender: VoiceGender? = null
    private val engines = HashMap<Language, OfflineTts>()

    override suspend fun synthesize(text: String, language: Language, gender: VoiceGender): SynthResult =
        withContext(Dispatchers.Default) {
            lock.withLock {
                if (loadedGender != null && loadedGender != gender) releaseLocked()
                loadedGender = gender
                val runs = ScriptSplitter.split(text, language)
                if (runs.isEmpty()) throw PiperUnavailableException("Nothing to synthesize")
                val parts = runs.map { run ->
                    val audio = engine(run.language, gender).generate(run.text, 0, 1.0f)
                    PcmJoin.floatToPcm16(audio.samples) to audio.sampleRate
                }
                val rate = parts.first().second
                SynthResult(
                    pcm = PcmJoin.concat(parts, rate),
                    sampleRate = rate,
                    voice = VoiceInfo("piper-${language.name.lowercase()}-${gender.name.lowercase()}", language, gender, bundled = true),
                )
            }
        }

    private fun engine(language: Language, gender: VoiceGender): OfflineTts = engines.getOrPut(language) {
        val dir = locator.dir(language, gender)
            ?: throw PiperUnavailableException("Piper voice missing: $language $gender")
        val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
            ?: throw PiperUnavailableException("Piper model missing in ${dir.name}")
        try {
            val cfg = OfflineTtsConfig()
            cfg.model.vits.model = onnx.absolutePath
            cfg.model.vits.tokens = File(dir, "tokens.txt").absolutePath
            cfg.model.vits.dataDir = File(dir, "espeak-ng-data").absolutePath
            cfg.model.numThreads = 2
            cfg.model.provider = "cpu"
            OfflineTts(null, cfg)
        } catch (t: Throwable) {
            throw PiperUnavailableException("Piper voice failed to load: ${dir.name}", t)
        }
    }

    /** True when both voices of [gender] exist on disk. */
    fun hasVoices(gender: VoiceGender): Boolean =
        Language.entries.all { locator.dir(it, gender) != null }

    /** Waits for a synthesis in flight (it holds the lock), then frees the models. */
    override fun release() = runBlocking { lock.withLock { releaseLocked() } }

    private fun releaseLocked() {
        engines.values.forEach { runCatching { it.release() } }
        engines.clear()
        loadedGender = null
    }
}
