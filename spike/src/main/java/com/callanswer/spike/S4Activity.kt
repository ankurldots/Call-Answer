package com.callanswer.spike

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * S4 screen. Plays 20 Agent turns (10 Hindi, 10 English, the S3 Polly clips) while capturing the microphone,
 * with the capture owner and source chosen as in S2. Afterwards each turn is run through three variants and
 * a result table is written to the log:
 *  RAW      all speech segments transcribed, no echo handling
 *  GATE+TXT EchoGate (segments fully inside an Agent window are not transcribed) plus EchoTextFilter
 *  AEC+G+T  the same after reference echo cancellation with the TTS PCM (only when the toggle is on)
 * Per turn the table says whether the Agent sentence is found in the Caller transcript (yes/no).
 * During the run the Caller (second phone on a call, or a person in the room) should talk over the Agent
 * on some turns; the kept transcript is logged so genuine Caller words can be checked by eye.
 */
class S4Activity : Activity() {
    private lateinit var status: TextView
    private lateinit var source: Spinner
    private lateinit var aec: CheckBox
    private lateinit var comm: CheckBox
    private lateinit var delay: EditText
    private lateinit var volume: EditText
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        title = "S4 Echo handling"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            textSize = 14f
            text = "Needs RECORD_AUDIO, the S2 capture service (plain foreground service, microphone type) started by the Start button and the Whisper " +
                "base model from S7 (spike/S7-README.md) for the transcripts. Start a call with the second phone, " +
                "put it on the loudspeaker, press Start. 20 turns play (10 Hindi, 10 English). Talk over the Agent " +
                "on some turns from the second phone. Results table goes to the log under S4; WAVs go to s4/."
        })
        source = Spinner(this).apply {
            adapter = ArrayAdapter(this@S4Activity, android.R.layout.simple_spinner_dropdown_item, S2Capture.SOURCES.map { it.first })
            setSelection(0)
        }
        aec = CheckBox(this).apply { text = "Also run reference AEC (NLMS, TTS PCM as reference)" }
        comm = CheckBox(this).apply { text = "Play with USAGE_VOICE_COMMUNICATION (off: USAGE_MEDIA)"; isChecked = true }
        delay = EditText(this).apply { hint = "Start delay, seconds"; setText("10"); inputType = 2 }
        volume = EditText(this).apply { hint = "Call volume %"; setText("80"); inputType = 2 }
        status = TextView(this).apply { textSize = 13f }
        root.addView(source); root.addView(aec); root.addView(comm); root.addView(delay); root.addView(volume)
        root.addView(Button(this).apply {
            text = "Start S4 (20 turns)"
            isAllCaps = false
            setOnClickListener { start() }
        })
        root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start() {
        if (running) { Toast.makeText(this, "Already running", Toast.LENGTH_SHORT).show(); return }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant RECORD_AUDIO on the main screen", Toast.LENGTH_LONG).show(); return
        }
        val owner: Context = this
        val ownerName = "FOREGROUND_SERVICE"
        startForegroundService(Intent(this, S2CaptureService::class.java).putExtra(S2CaptureService.EXTRA_HOLD, true))
        val srcIdx = source.selectedItemPosition
        val useAec = aec.isChecked
        val usage = if (comm.isChecked) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val d = delay.text.toString().toIntOrNull() ?: 0
        val vol = (volume.text.toString().toIntOrNull() ?: 80).coerceIn(0, 100)
        running = true
        Thread {
            try {
                Thread.sleep(d * 1000L)
                S4Runner(this, owner, ownerName, srcIdx, useAec, usage, vol) { msg -> runOnUiThread { status.text = msg } }.run()
            } catch (e: Throwable) {
                SpikeLog.log("S4", "run failed: $e")
                runOnUiThread { status.text = "Failed: $e" }
            } finally {
                stopService(Intent(this, S2CaptureService::class.java))
                running = false
            }
        }.start()
        Toast.makeText(this, "Started. Place the call now.", Toast.LENGTH_LONG).show()
    }
}

private class S4Turn(val index: Int, val lang: String, val clip: Int, val text: String)

private class S4Capture(val pcm: ShortArray, val n: Int, val window: AgentWindow, val refPcm: FloatArray, val refStartSample: Int)

private class S4Runner(
    private val activity: Activity,
    private val owner: Context,
    private val ownerName: String,
    private val sourceIndex: Int,
    private val useAec: Boolean,
    private val usage: Int,
    private val volumePercent: Int,
    private val progress: (String) -> Unit,
) {
    private val rate = 16_000
    private val tag = "S4"

    fun run() {
        val turns = loadTurns()
        val (srcName, srcId) = S2Capture.SOURCES[sourceIndex]
        SpikeLog.log(tag, "run start owner=$ownerName source=$srcName usage=${if (usage == AudioAttributes.USAGE_MEDIA) "MEDIA" else "VOICE_COMMUNICATION"} " +
            "callVolume=${AudioPlayer.setCallVolumePercent(activity, volumePercent)} aec=$useAec turns=${turns.size}")
        val player = AudioPlayer(activity)
        val captures = ArrayList<S4Capture>()
        for (t in turns) {
            progress("Capturing turn ${t.index}/${turns.size} (${t.lang} ${t.clip})")
            captures.add(captureTurn(player, t, srcId))
        }
        player.stop()

        progress("Loading ASR")
        val models = S7Models(activity)
        val asr = S7Whisper(models)
        val asrOk = try { asr.load(); true } catch (e: Throwable) {
            SpikeLog.log(tag, "ASR unavailable (${e.message}); WAVs are saved, no table"); false
        }
        if (!asrOk) { progress("No ASR model. WAVs saved to s4/. See log."); return }

        SpikeLog.log(tag, "turn | lang | segs | gateDrop | textDrop | RAW found | GATE+TXT found | AEC+G+T found | aecDelayMs | Caller transcript kept")
        var rawYes = 0; var gtYes = 0; var aecYes = 0
        try {
            for ((i, t) in turns.withIndex()) {
                progress("Analysing turn ${t.index}/${turns.size}")
                val c = captures[i]
                val base = analyse(asr, c.pcm, c.n, c.window)
                var aecFound = "-"
                var delayMs = -1
                if (useAec) {
                    val (clean, dly) = S4Aec.cancel(c.pcm, c.n, c.refPcm, c.refStartSample)
                    delayMs = dly
                    val a = analyse(asr, clean, c.n, c.window)
                    val f = EchoTextFilter.agentTextFound(a.kept, t.text)
                    aecFound = if (f) "yes" else "no"
                    if (f) aecYes++
                }
                val rawF = EchoTextFilter.agentTextFound(base.raw, t.text)
                val gtF = EchoTextFilter.agentTextFound(base.kept, t.text)
                if (rawF) rawYes++
                if (gtF) gtYes++
                SpikeLog.log(tag, "${t.index} | ${t.lang}${t.clip} | ${base.segments} | ${base.gateDropped} | ${base.textDropped} | " +
                    "${yn(rawF)} | ${yn(gtF)} | $aecFound | ${if (delayMs >= 0) delayMs else "-"} | ${base.kept.take(80)}")
            }
        } finally {
            asr.release()
        }
        val n = turns.size
        val verdict = if (gtYes == 0) "PASS: no Agent text in the Caller transcript with gate + text filter"
        else "FAIL: Agent text leaked in $gtYes of $n turns with gate + text filter"
        SpikeLog.log(tag, "summary turns=$n agentTextFound RAW=$rawYes GATE+TXT=$gtYes " +
            "AEC+G+T=${if (useAec) aecYes.toString() else "not run"} -> $verdict. AEC layer needed: " +
            if (gtYes == 0) "no" else if (useAec && aecYes < gtYes) "yes (AEC reduced leaks from $gtYes to $aecYes)" else "undecided, run with AEC on")
        progress("Done. $verdict. Table in log.")
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"

    private class Analysis(val segments: Int, val gateDropped: Int, val textDropped: Int, val raw: String, val kept: String)

    private fun analyse(asr: S7Whisper, pcm: ShortArray, n: Int, window: AgentWindow): Analysis {
        val windows = listOf(window)
        val gate = EchoGate(windows)
        val segs = S4Segmenter.segments(pcm, n)
        val raw = StringBuilder(); val kept = StringBuilder()
        var gateDropped = 0; var textDropped = 0
        for (s in segs) {
            // Layer 1 saves the ASR call; RAW needs the text anyway, so transcribe every segment here.
            val a = (s.startSec * rate).toInt().coerceIn(0, n)
            val b = (s.endSec * rate).toInt().coerceIn(a, n)
            val f = FloatArray(b - a) { pcm[a + it] / 32768f }
            val text = if (f.isEmpty()) "" else asr.transcribe(S7VadResult(f, f.size, f.size, 0.0), f).text.trim()
            raw.append(text).append(' ')
            if (gate.fullyInside(s)) { gateDropped++; continue }
            if (EchoTextFilter.isEcho(text, s, gate, windows)) { textDropped++; continue }
            kept.append(text).append(' ')
        }
        return Analysis(segs.size, gateDropped, textDropped, raw.toString().trim(), kept.toString().trim())
    }

    private fun loadTurns(): List<S4Turn> {
        fun lines(f: String) = activity.assets.open("s4/$f").bufferedReader().readLines().filter { it.isNotBlank() }
        val hi = lines("hi.txt"); val en = lines("en.txt")
        val out = ArrayList<S4Turn>()
        for (i in 0 until 10) {
            out.add(S4Turn(out.size + 1, "hi", i + 1, hi[i]))
            out.add(S4Turn(out.size + 1, "en", i + 1, en[i]))
        }
        return out
    }

    private fun captureTurn(player: AudioPlayer, t: S4Turn, srcId: Int): S4Capture {
        val asset = "s3/${t.lang}_%02d.wav".format(t.clip)
        val (wavRate, refShorts) = readWav(activity.assets.open(asset).use { it.readBytes() })
        val ref = resample(refShorts, wavRate)
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioSource(srcId)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        val leadSec = 1.0
        val clipSec = ref.size.toDouble() / rate
        val totalSec = leadSec + clipSec + EchoGate.POST_SEC + 2.0 // 2 s tail for the Caller's reply
        val pcm = ShortArray((totalSec * rate).toInt())
        var n = 0
        var ttsStart = 0.0
        var t0 = 0L
        try {
            rec.startRecording()
            t0 = System.nanoTime()
            var started = false
            while (n < pcm.size) {
                val now = (System.nanoTime() - t0) / 1e9
                if (!started && now >= leadSec) {
                    player.play(asset, usage)
                    ttsStart = (System.nanoTime() - t0) / 1e9
                    started = true
                }
                val r = rec.read(pcm, n, minOf(800, pcm.size - n))
                if (r < 0) break
                n += r
            }
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            player.stop()
        }
        val win = AgentWindow(ttsStart, ttsStart + clipSec, t.text)
        dump(t, pcm, n)
        SpikeLog.log(tag, "turn ${t.index} ${t.lang}${t.clip} captured samples=$n ttsStart=%.3f ttsEnd=%.3f".format(win.ttsStartSec, win.ttsEndSec))
        return S4Capture(pcm, n, win, ref, (ttsStart * rate).toInt())
    }

    private fun readWav(b: ByteArray): Pair<Int, ShortArray> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var r = 16_000
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") r = bb.getInt(pos + 12)
            if (id == "data") {
                val len = minOf(size, b.size - pos - 8) / 2
                return r to ShortArray(len) { bb.getShort(pos + 8 + it * 2) }
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    private fun resample(s: ShortArray, from: Int): FloatArray {
        if (from == rate) return FloatArray(s.size) { s[it] / 32768f }
        val len = (s.size.toLong() * rate / from).toInt()
        return FloatArray(len) { i ->
            val p = i.toDouble() * from / rate
            val a = p.toInt().coerceAtMost(s.size - 1)
            val b = (a + 1).coerceAtMost(s.size - 1)
            val fr = (p - a).toFloat()
            (s[a] * (1 - fr) + s[b] * fr) / 32768f
        }
    }

    private fun dump(t: S4Turn, pcm: ShortArray, n: Int) {
        try {
            val dir = File(activity.getExternalFilesDir(null) ?: activity.filesDir, "s4").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val h = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(n * 2)
            for (i in 0 until n) h.putShort(pcm[i])
            File(dir, "s4-turn%02d-%s%02d-%s.wav".format(t.index, t.lang, t.clip, stamp)).writeBytes(h.array())
        } catch (e: Throwable) {
            SpikeLog.log(tag, "WAV dump failed: $e")
        }
    }
}
