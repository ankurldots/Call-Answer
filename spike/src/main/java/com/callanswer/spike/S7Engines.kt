package com.callanswer.spike

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Model locations for S7. Nothing is bundled; see spike/S7-README.md. */
class S7Models(context: Context) {
    val root: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "s7-models")

    private fun dirStartingWith(prefix: String): File? =
        root.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith(prefix) }

    val whisperDir: File? get() = dirStartingWith("whisper-base")
    val voskHiDir: File? get() = dirStartingWith("vosk-model-small-hi")
    val voskEnInDir: File? get() = dirStartingWith("vosk-model-small-en-in")
    val sileroVad: File get() = File(root, "silero_vad.onnx")
    val piperEnDir: File? get() = dirStartingWith("piper-en")
    val piperHiDir: File? get() = dirStartingWith("piper-hi")

    fun report(): List<String> = listOf(
        "models root: ${root.absolutePath}",
        "whisper-base*: ${whisperDir?.name ?: "MISSING"}",
        "vosk-model-small-hi*: ${voskHiDir?.name ?: "MISSING"}",
        "vosk-model-small-en-in*: ${voskEnInDir?.name ?: "MISSING"}",
        "silero_vad.onnx: ${if (sileroVad.exists()) "ok" else "MISSING"}",
        "piper-en*: ${piperEnDir?.name ?: "MISSING"}",
        "piper-hi*: ${piperHiDir?.name ?: "MISSING"}",
    )
}

/** Result of the VAD pass over one utterance. Sample indices are at 16 kHz. */
class S7VadResult(
    val speech: FloatArray,
    val speechEndSample: Int,
    val endpointSample: Int,
    val computeMs: Double,
) {
    /** Audio-time delay between the last speech sample and the moment the VAD reports the endpoint. */
    val endpointDelayMs: Double get() = (endpointSample - speechEndSample) / 16.0
}

class S7Vad(models: S7Models) {
    private val vad: Vad

    init {
        val silero = SileroVadModelConfig()
        silero.model = models.sileroVad.absolutePath
        silero.threshold = 0.5f
        silero.minSilenceDuration = 0.5f
        silero.minSpeechDuration = 0.25f
        silero.windowSize = 512
        val cfg = VadModelConfig()
        cfg.sileroVadModelConfig = silero
        cfg.sampleRate = 16000
        cfg.numThreads = 1
        cfg.provider = "cpu"
        vad = Vad(null, cfg)
    }

    /** Streams the utterance plus 1.2 s of trailing silence through Silero in 512 sample windows. */
    fun run(pcm: FloatArray): S7VadResult {
        val t0 = System.nanoTime()
        vad.reset()
        val padded = pcm.copyOf(pcm.size + 19200)
        val segments = ArrayList<FloatArray>()
        var lastEnd = pcm.size
        var endpoint = padded.size
        var pos = 0
        fun drain(fed: Int) {
            while (!vad.empty()) {
                val seg = vad.front()
                segments.add(seg.samples)
                lastEnd = seg.start.toInt() + seg.samples.size
                endpoint = fed
                vad.pop()
            }
        }
        while (pos + 512 <= padded.size) {
            vad.acceptWaveform(padded.copyOfRange(pos, pos + 512))
            pos += 512
            drain(pos)
        }
        vad.flush()
        drain(padded.size)
        val speech = if (segments.isEmpty()) pcm else segments.fold(FloatArray(0)) { a, b -> a + b }
        if (segments.isEmpty()) {
            lastEnd = pcm.size
            endpoint = pcm.size
        }
        return S7VadResult(speech, lastEnd, endpoint, (System.nanoTime() - t0) / 1e6)
    }

    fun release() = vad.release()
}

/** ASR output. [tailMs] is the decode time that remains after the endpoint (what the Caller waits for). */
class S7AsrOut(val text: String, val tailMs: Double)

interface S7Asr {
    val name: String
    fun load()
    fun transcribe(vad: S7VadResult, fullPcm: FloatArray): S7AsrOut
    fun release()
}

/** Whisper base int8 through sherpa-onnx, 2 threads. Decodes the VAD speech after the endpoint. */
class S7Whisper(private val models: S7Models) : S7Asr {
    override val name = "whisper-base-int8"
    private var rec: OfflineRecognizer? = null

    override fun load() {
        val dir = models.whisperDir ?: error("whisper-base* folder missing")
        fun pick(kind: String): String {
            val files = dir.listFiles { f -> f.name.contains(kind) && f.name.endsWith(".onnx") }.orEmpty()
            return (files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull())?.absolutePath
                ?: error("whisper $kind onnx missing in ${dir.path}")
        }
        val tokens = dir.listFiles { f -> f.name.endsWith("tokens.txt") }?.firstOrNull()?.absolutePath
            ?: error("whisper tokens.txt missing")
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
        rec = OfflineRecognizer(null, cfg)
    }

    override fun transcribe(vad: S7VadResult, fullPcm: FloatArray): S7AsrOut {
        val r = rec ?: error("not loaded")
        val t0 = System.nanoTime()
        val stream = r.createStream()
        stream.acceptWaveform(vad.speech, 16000)
        r.decode(stream)
        val text = r.getResult(stream).text
        stream.release()
        return S7AsrOut(text, (System.nanoTime() - t0) / 1e6)
    }

    override fun release() { rec?.release(); rec = null }
}

/** Vosk small streaming model. Audio is fed in 100 ms chunks up to the endpoint; the tail is the last chunk plus final result. */
class S7Vosk(override val name: String, private val dirProvider: () -> File?) : S7Asr {
    private var model: Model? = null

    override fun load() {
        val dir = dirProvider() ?: error("$name model folder missing")
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        model = Model(dir.absolutePath)
    }

    override fun transcribe(vad: S7VadResult, fullPcm: FloatArray): S7AsrOut {
        val m = model ?: error("not loaded")
        val rec = Recognizer(m, 16000f)
        val parts = ArrayList<String>()
        val end = vad.endpointSample.coerceAtMost(fullPcm.size)
        val chunk = 1600
        var pos = 0
        var tail = 0.0
        while (pos < end) {
            val n = minOf(chunk, end - pos)
            val shorts = ShortArray(n) { (fullPcm[pos + it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            val t0 = System.nanoTime()
            if (rec.acceptWaveForm(shorts, n)) parts.add(JSONObject(rec.result).optString("text"))
            tail = (System.nanoTime() - t0) / 1e6
            pos += n
        }
        val t1 = System.nanoTime()
        parts.add(JSONObject(rec.finalResult).optString("text"))
        tail += (System.nanoTime() - t1) / 1e6
        rec.close()
        return S7AsrOut(parts.filter { it.isNotBlank() }.joinToString(" "), tail)
    }

    override fun release() { model?.close(); model = null }
}

/** Piper through sherpa-onnx. Measures time to the first audio callback. */
class S7Piper(private val models: S7Models) {
    private val tts = HashMap<String, OfflineTts>()
    val loadMs = HashMap<String, Double>()

    fun available(lang: String) = dirFor(lang) != null

    private fun dirFor(lang: String): File? = if (lang == "hi") models.piperHiDir else models.piperEnDir

    private fun engine(lang: String): OfflineTts = tts.getOrPut(lang) {
        val dir = dirFor(lang) ?: error("piper-$lang folder missing")
        val t0 = System.nanoTime()
        val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull() ?: error("piper onnx missing")
        val cfg = OfflineTtsConfig()
        cfg.model.vits.model = onnx.absolutePath
        cfg.model.vits.tokens = File(dir, "tokens.txt").absolutePath
        cfg.model.vits.dataDir = File(dir, "espeak-ng-data").absolutePath
        cfg.model.numThreads = 2
        cfg.model.provider = "cpu"
        val e = OfflineTts(null, cfg)
        loadMs[lang] = (System.nanoTime() - t0) / 1e6
        e
    }

    /** Returns milliseconds from the generate call until the first audio chunk arrives. */
    fun firstAudioMs(lang: String, text: String): Double {
        val e = engine(lang)
        val t0 = System.nanoTime()
        var first = -1.0
        e.generateWithCallback(text, 0, 1.0f) {
            if (first < 0) first = (System.nanoTime() - t0) / 1e6
            1
        }
        return if (first < 0) (System.nanoTime() - t0) / 1e6 else first
    }

    fun release() { tts.values.forEach { it.release() }; tts.clear() }
}
