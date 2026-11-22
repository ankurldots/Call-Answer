package com.callanswer.app.asr

import android.os.SystemClock
import com.callanswer.app.audio.SpeechSegment
import com.callanswer.core.model.Language
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Whisper base int8 through sherpa-onnx: 2 threads, one decode per utterance after the endpoint, language auto-detected. */
class WhisperRecognizer(private val models: AsrModelStore) : SpeechRecognizerPort {
    private val lock = Mutex()
    private var rec: OfflineRecognizer? = null

    private fun load(): OfflineRecognizer {
        rec?.let { return it }
        val dir = models.dir(AsrModelStore.WHISPER) ?: error("Whisper model not bundled")
        fun pick(kind: String): String {
            val files = dir.listFiles { f: File -> f.name.contains(kind) && f.name.endsWith(".onnx") }.orEmpty()
            return (files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull())?.absolutePath
                ?: error("Whisper $kind model missing")
        }
        val tokens = dir.listFiles { f: File -> f.name.endsWith("tokens.txt") }?.firstOrNull()?.absolutePath
            ?: error("Whisper tokens.txt missing")
        val cfg = OfflineRecognizerConfig()
        cfg.modelConfig.whisper.encoder = pick("encoder")
        cfg.modelConfig.whisper.decoder = pick("decoder")
        cfg.modelConfig.whisper.language = "" // auto-detect: Hindi, English and Hinglish share one recognizer
        cfg.modelConfig.whisper.task = "transcribe"
        cfg.modelConfig.tokens = tokens
        cfg.modelConfig.numThreads = 2
        cfg.modelConfig.provider = "cpu"
        cfg.modelConfig.modelType = "whisper"
        cfg.decodingMethod = "greedy_search"
        return OfflineRecognizer(null, cfg).also { rec = it }
    }

    override suspend fun transcribe(segment: SpeechSegment, languageHint: Language?): AsrResult =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val t0 = SystemClock.elapsedRealtime()
                val r = load()
                val stream = r.createStream()
                try {
                    stream.acceptWaveform(segment.samples.toFloatPcm(), 16000)
                    r.decode(stream)
                    val res = r.getResult(stream)
                    val tag = when (res.lang.trim('<', '|', '>', ' ').lowercase()) {
                        "hi" -> Language.HI
                        "en" -> Language.EN
                        else -> null
                    }
                    AsrResult(res.text.trim(), tag, SystemClock.elapsedRealtime() - t0)
                } finally {
                    stream.release()
                }
            }
        }

    override fun release() {
        rec?.release()
        rec = null
    }
}
