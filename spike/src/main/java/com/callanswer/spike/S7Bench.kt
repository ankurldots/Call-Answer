package com.callanswer.spike

import android.content.Context
import android.os.Debug
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class S7Utterance(val id: String, val lang: String, val expectedIntent: String, val text: String, val pcm: FloatArray)

class S7Row(
    val engine: String,
    val utt: S7Utterance,
    val hyp: String,
    val errors: Int,
    val refWords: Int,
    val intent: String,
    val vadDelayMs: Double,
    val asrTailMs: Double,
    val classifyMs: Double,
    val piperMs: Double?, // null when Piper models are missing
    val atMs: Long, // elapsed since the pass started
) {
    val e2eMs: Double get() = vadDelayMs + asrTailMs + classifyMs + (piperMs ?: 0.0)
}

/** S7 harness: 30 TTS utterances through Silero VAD, one ASR engine, the draft intent table and Piper. */
class S7Bench(private val context: Context, private val emit: (String) -> Unit) {
    private val models = S7Models(context)

    @Volatile
    var cancelled = false

    @Volatile
    private var peakPssKb = 0

    fun modelReport(): List<String> = models.report()

    fun loadCorpus(): List<S7Utterance> {
        val am = context.assets
        val lines = am.open("s7/reference.tsv").bufferedReader(Charsets.UTF_8).readLines().drop(1).filter { it.isNotBlank() }
        return lines.map { line ->
            val c = line.split("\t")
            S7Utterance(c[0], c[1], c[3], c[4], readWav(am.open("s7/wav/${c[0]}.wav").readBytes()))
        }
    }

    private fun readWav(b: ByteArray): FloatArray {
        var off = 12
        while (off + 8 <= b.size) {
            val id = String(b, off, 4, Charsets.US_ASCII)
            val len = ByteBuffer.wrap(b, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val n = minOf(len, b.size - off - 8) / 2
                val bb = ByteBuffer.wrap(b, off + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(n) { bb.short / 32768f }
            }
            off += 8 + len
        }
        error("no data chunk in WAV")
    }

    /** Pins the calling thread (and the ORT threads it later spawns) to the big cores, if the OS allows it. */
    private fun tryBigCoreAffinity() {
        try {
            val freqs = (0 until Runtime.getRuntime().availableProcessors()).associateWith {
                File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }
            val max = freqs.values.max()
            val big = freqs.filterValues { it == max }.keys
            val mask = big.fold(0) { m, i -> m or (1 shl i) }
            val tid = android.os.Process.myTid()
            val p = ProcessBuilder("taskset", "-p", Integer.toHexString(mask), tid.toString()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim().replace("\n", " | ")
            val code = p.waitFor()
            emit("big cores $big mask=0x${Integer.toHexString(mask)} taskset exit=$code: $out")
        } catch (e: Exception) {
            emit("big-core affinity not applied: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startPssSampler(): Thread {
        peakPssKb = 0
        val t = Thread {
            val mi = Debug.MemoryInfo()
            while (!Thread.currentThread().isInterrupted) {
                Debug.getMemoryInfo(mi)
                if (mi.totalPss > peakPssKb) peakPssKb = mi.totalPss
                try { Thread.sleep(300) } catch (_: InterruptedException) { break }
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun sample() {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        if (mi.totalPss > peakPssKb) peakPssKb = mi.totalPss
    }

    private val piperPhrase = mapOf(
        "hi" to "कृपया पार्सल गार्ड के पास छोड़ दीजिए, धन्यवाद।",
        "en" to "Please leave the parcel with the guard, thank you.",
    )

    fun engines(): List<S7Asr> = listOf(
        S7Whisper(models),
        S7Vosk("vosk-small-hi") { models.voskHiDir },
        S7Vosk("vosk-small-en-in") { models.voskEnInDir },
    )

    private fun runOne(
        asr: S7Asr, vad: S7Vad, piper: S7Piper, u: S7Utterance, startNs: Long,
    ): S7Row {
        val v = vad.run(u.pcm)
        val a = asr.transcribe(v, u.pcm)
        val c0 = System.nanoTime()
        val intent = S7Metrics.classify(a.text)
        val classifyMs = (System.nanoTime() - c0) / 1e6
        val pLang = if (u.lang == "hi") "hi" else "en"
        val piperMs = if (piper.available(pLang)) piper.firstAudioMs(pLang, piperPhrase.getValue(pLang)) else null
        val ref = S7Metrics.words(u.text)
        val errs = S7Metrics.wordErrors(ref, S7Metrics.words(a.text))
        sample()
        return S7Row(asr.name, u, a.text, errs, ref.size, intent, v.endpointDelayMs, a.tailMs, classifyMs, piperMs, (System.nanoTime() - startNs) / 1_000_000)
    }

    /**
     * Cold pass: engines are freshly loaded and every utterance runs once with no warm-up.
     * Sustained pass: [sustainedMs] > 0 loops the corpus on [onlyEngine] after loading it.
     */
    fun run(sustainedMs: Long, onlyEngine: String?) {
        cancelled = false
        val corpus = loadCorpus()
        emit("corpus: ${corpus.size} utterances from assets/s7 (Polly Kajal neural TTS)")
        modelReport().forEach { emit(it) }
        emit("NOTE: TTS audio is clean and flatters recognizers. Add real recorded speech (S2 dumps) before trusting WER or intent accuracy.")
        if (!models.sileroVad.exists()) { emit("ABORT: silero_vad.onnx missing"); return }
        tryBigCoreAffinity()
        val sampler = startPssSampler()
        val vad = S7Vad(models)
        val piper = S7Piper(models)
        try {
            val list = engines().filter { onlyEngine == null || it.name == onlyEngine }
            for (asr in list) {
                if (cancelled) break
                val loadStart = System.nanoTime()
                try { asr.load() } catch (e: Throwable) { emit("${asr.name}: load failed: ${e.message}"); continue }
                val loadMs = (System.nanoTime() - loadStart) / 1e6
                emit("${asr.name}: model load ${"%.0f".format(loadMs)} ms")
                val rows = ArrayList<S7Row>()
                val passStart = System.nanoTime()
                val deadline = passStart + sustainedMs * 1_000_000
                var loops = 0
                do {
                    for (u in corpus) {
                        if (cancelled) break
                        rows.add(runOne(asr, vad, piper, u, passStart))
                        if (sustainedMs > 0 && System.nanoTime() > deadline) break
                    }
                    loops++
                } while (sustainedMs > 0 && System.nanoTime() < deadline && !cancelled)
                val label = if (sustainedMs > 0) "sustained(${sustainedMs / 60000.0} min, $loops loops)" else "cold"
                report(asr.name, label, rows, loadMs, corpus.size)
                if (sustainedMs == 0L) rows.forEach {
                    emit("  ${it.utt.id} intent exp=${it.utt.expectedIntent} got=${it.intent} e2e=${"%.0f".format(it.e2eMs)}ms hyp=\"${it.hyp}\"")
                }
                asr.release()
            }
            piper.loadMs.forEach { (l, ms) -> emit("piper-$l: model load ${"%.0f".format(ms)} ms") }
        } finally {
            sampler.interrupt()
            vad.release()
            piper.release()
        }
        emit("S7 done. Peak PSS over this run: ${peakPssKb / 1024} MB")
    }

    private fun pct(v: List<Double>, p: Double) = S7Metrics.percentile(v, p).let { if (it.isNaN()) "n/a" else "%.0f".format(it) }

    private fun report(engine: String, label: String, rows: List<S7Row>, loadMs: Double, corpusSize: Int) {
        if (rows.isEmpty()) { emit("$engine $label: no rows"); return }
        fun wer(rs: List<S7Row>) = if (rs.isEmpty()) "n/a" else "%.1f%%".format(100.0 * rs.sumOf { it.errors } / rs.sumOf { it.refWords }.coerceAtLeast(1))
        fun acc(rs: List<S7Row>) = if (rs.isEmpty()) 0.0 else 100.0 * rs.count { it.intent == it.utt.expectedIntent } / rs.size
        val e2e = rows.map { it.e2eMs }
        emit("RESULT | engine | pass | n | WER all (hi/en/hinglish) | intent acc | e2e p50/p90 ms | VAD endpoint p50 ms | ASR tail p50/p90 ms | Piper first audio p50/p90 ms | load ms | peak PSS MB")
        emit(
            "RESULT | $engine | $label | ${rows.size} | ${wer(rows)} (${wer(rows.filter { it.utt.lang == "hi" })}/" +
                "${wer(rows.filter { it.utt.lang == "en" })}/${wer(rows.filter { it.utt.lang == "hinglish" })}) | " +
                "${"%.1f".format(acc(rows))}% | ${pct(e2e, 50.0)}/${pct(e2e, 90.0)} | ${pct(rows.map { it.vadDelayMs }, 50.0)} | " +
                "${pct(rows.map { it.asrTailMs }, 50.0)}/${pct(rows.map { it.asrTailMs }, 90.0)} | " +
                "${pct(rows.mapNotNull { it.piperMs }, 50.0)}/${pct(rows.mapNotNull { it.piperMs }, 90.0)} | ${"%.0f".format(loadMs)} | ${peakPssKb / 1024}"
        )
        if (label.startsWith("sustained")) {
            val lastStart = rows.last().atMs - 120_000
            val late = rows.filter { it.atMs >= lastStart }.map { it.e2eMs }
            val early = rows.take(corpusSize).map { it.e2eMs }
            emit("RESULT | $engine | sustained first loop e2e p50/p90 ${pct(early, 50.0)}/${pct(early, 90.0)} ms | last 2 min e2e p50/p90 ${pct(late, 50.0)}/${pct(late, 90.0)} ms (n=${late.size})")
        }
        if (engine == "whisper-base-int8") {
            val ok = S7Metrics.percentile(e2e, 90.0) <= 2500.0 && acc(rows) >= 90.0
            emit("RULE | $engine $label: e2e p90 <= 2500 ms and intent >= 90% => ${if (ok) "PASS" else "FAIL"} (need both cold and sustained to pass for WHISPER_BASE_INT8)")
        }
    }
}
