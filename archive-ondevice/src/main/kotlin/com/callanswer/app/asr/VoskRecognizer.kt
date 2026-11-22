package com.callanswer.app.asr

import android.os.SystemClock
import com.callanswer.app.audio.SpeechSegment
import com.callanswer.core.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Vosk small `hi` and `en-in` models, audio fed in 100 ms chunks. With a language hint only that model runs;
 * without one both run and the result with more words wins. Models load on first use and are closed in [release].
 */
class VoskRecognizer(private val models: AsrModelStore) : SpeechRecognizerPort {
    private val lock = Mutex()
    private val loaded = HashMap<Language, Model>()

    private fun model(language: Language): Model = synchronized(loaded) {
        loaded.getOrPut(language) {
            val name = if (language == Language.HI) AsrModelStore.VOSK_HI else AsrModelStore.VOSK_EN_IN
            val dir = models.dir(name) ?: error("Vosk model $name not bundled")
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            Model(dir.absolutePath)
        }
    }

    private fun decode(language: Language, pcm: ShortArray): String {
        val rec = Recognizer(model(language), 16000f)
        try {
            val parts = ArrayList<String>()
            var pos = 0
            while (pos < pcm.size) {
                val n = minOf(CHUNK, pcm.size - pos)
                val chunk = if (pos == 0 && n == pcm.size) pcm else pcm.copyOfRange(pos, pos + n)
                if (rec.acceptWaveForm(chunk, n)) parts.add(JSONObject(rec.result).optString("text"))
                pos += n
            }
            parts.add(JSONObject(rec.finalResult).optString("text"))
            return parts.filter { it.isNotBlank() }.joinToString(" ")
        } finally {
            rec.close()
        }
    }

    override suspend fun transcribe(segment: SpeechSegment, languageHint: Language?): AsrResult =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val t0 = SystemClock.elapsedRealtime()
                val pcm = segment.samples
                if (languageHint != null) {
                    AsrResult(decode(languageHint, pcm), languageHint, SystemClock.elapsedRealtime() - t0)
                } else {
                    val (hi, en) = coroutineScope {
                        val h = async { decode(Language.HI, pcm) }
                        val e = async { decode(Language.EN, pcm) }
                        h.await() to e.await()
                    }
                    fun words(s: String) = s.split(' ').count { it.isNotBlank() }
                    val (text, tag) = if (words(hi) >= words(en)) hi to Language.HI else en to Language.EN
                    AsrResult(text, if (text.isBlank()) null else tag, SystemClock.elapsedRealtime() - t0)
                }
            }
        }

    override fun release() {
        synchronized(loaded) {
            loaded.values.forEach { it.close() }
            loaded.clear()
        }
    }

    private companion object {
        const val CHUNK = 1600
    }
}
