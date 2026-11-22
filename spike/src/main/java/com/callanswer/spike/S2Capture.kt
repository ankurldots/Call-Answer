package com.callanswer.spike

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Run guard: only one S2 capture at a time. */
object S2Bridge {
    @Volatile var running: Boolean = false
}

/**
 * S2: captures 16 kHz mono PCM16 from each source in turn, logs isClientSilenced() changes,
 * writes one WAV per source to app storage and logs an RMS summary.
 */
object S2Capture {
    private const val RATE = 16_000
    val SOURCES = listOf(
        "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "MIC" to MediaRecorder.AudioSource.MIC,
        "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
        "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    )

    /**
     * Blocks until the phone state becomes OFFHOOK (TelephonyCallback on API 31+, else PhoneStateListener)
     * or [timeoutSec] passes. Returns true if OFFHOOK was seen. Needs READ_PHONE_STATE.
     */
    @Suppress("DEPRECATION")
    fun waitForOffhook(context: Context, timeoutSec: Int): Boolean {
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        val latch = java.util.concurrent.CountDownLatch(1)
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val cb = if (android.os.Build.VERSION.SDK_INT >= 31) {
            object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    if (state == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK) latch.countDown()
                }
            }
        } else null
        val legacy = if (cb == null) object : android.telephony.PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK) latch.countDown()
            }
        } else null
        return try {
            if (cb != null) tm.registerTelephonyCallback(exec, cb)
            else android.os.Handler(android.os.Looper.getMainLooper()).post { tm.listen(legacy, android.telephony.PhoneStateListener.LISTEN_CALL_STATE) }
            SpikeLog.log("S2", "waiting for OFFHOOK (up to $timeoutSec s)")
            val ok = latch.await(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            SpikeLog.log("S2", if (ok) "OFFHOOK seen, starting capture" else "OFFHOOK not seen within $timeoutSec s, starting anyway")
            if (ok) Thread.sleep(1500) // let the in-call audio path settle
            ok
        } catch (e: Throwable) {
            SpikeLog.log("S2", "waitForOffhook failed: $e"); false
        } finally {
            try {
                if (cb != null) tm.unregisterTelephonyCallback(cb)
                else android.os.Handler(android.os.Looper.getMainLooper()).post { tm.listen(legacy, android.telephony.PhoneStateListener.LISTEN_NONE) }
            } catch (_: Throwable) {}
            exec.shutdown()
        }
    }

    /** Blocking. Call from a worker thread. [seconds] is the capture length per source. */
    fun runAll(context: Context, seconds: Int, only: String? = null, origin: String = "FGS") {
        if (S2Bridge.running) { SpikeLog.log("S2", "run ignored: already running"); return }
        S2Bridge.running = true
        try {
            val am = context.getSystemService(AudioManager::class.java)
            SpikeLog.log("S2", "run start origin=$origin assistantRoleHeld=${S2bAssistant.roleHeld(context)} secondsPerSource=$seconds source=${only ?: "ALL"} audioMode=${modeName(am.mode)} " +
                "speakerphone=${am.isSpeakerphoneOn}")
            for ((name, src) in SOURCES.filter { only == null || it.first == only }) captureOne(context, am, name, src, seconds, origin)
            SpikeLog.log("S2", "run done")
        } catch (e: Throwable) {
            SpikeLog.log("S2", "run failed: $e")
        } finally {
            S2Bridge.running = false
        }
    }

    private fun captureOne(ctx: Context, am: AudioManager, name: String, source: Int, seconds: Int, origin: String) {
        val tag = "$origin/$name"
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { SpikeLog.log("S2", "$tag getMinBufferSize=$minBuf, skipped"); return }
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(minBuf * 4)
                .build()
        } catch (e: Throwable) {
            SpikeLog.log("S2", "$tag AudioRecord build failed: $e"); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            SpikeLog.log("S2", "$tag AudioRecord not initialized"); rec.release(); return
        }
        val ht = HandlerThread("s2-cb").also { it.start() }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }
                if (mine == null) SpikeLog.log("S2", "$tag callback: own config not listed (${configs.size} configs)")
                else SpikeLog.log("S2", "$tag callback isClientSilenced=${mine.isClientSilenced} " +
                    "clientSource=${mine.clientAudioSource} audioMode=${modeName(am.mode)}")
            }
        }
        am.registerAudioRecordingCallback(callback, Handler(ht.looper))
        val pcm = ShortArray(RATE * seconds)
        var n = 0
        try {
            rec.startRecording()
            SpikeLog.log("S2", "$tag started recordingState=${rec.recordingState} audioMode=${modeName(am.mode)} " +
                "silencedNow=${rec.activeRecordingConfiguration?.isClientSilenced}")
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            while (n < pcm.size && System.nanoTime() < deadline) {
                val r = rec.read(pcm, n, minOf(1600, pcm.size - n))
                if (r < 0) { SpikeLog.log("S2", "$tag read error $r"); break }
                n += r
            }
            SpikeLog.log("S2", "$tag finished silencedAtEnd=${rec.activeRecordingConfiguration?.isClientSilenced}")
        } catch (e: Throwable) {
            SpikeLog.log("S2", "$tag capture failed: $e")
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            am.unregisterAudioRecordingCallback(callback)
            ht.quitSafely()
        }
        summarize(ctx, tag, "$origin-$name", pcm, n)
    }

    private fun summarize(ctx: Context, tag: String, name: String, pcm: ShortArray, n: Int) {
        if (n == 0) { SpikeLog.log("S2", "$tag no samples"); return }
        var sumSq = 0.0; var peak = 0; var zeros = 0
        for (i in 0 until n) {
            val v = pcm[i].toInt()
            sumSq += v.toDouble() * v
            if (abs(v) > peak) peak = abs(v)
            if (v == 0) zeros++
        }
        val rms = sqrt(sumSq / n)
        val dbfs = if (rms > 0) 20 * log10(rms / 32768.0) else Double.NEGATIVE_INFINITY
        // Per-second RMS shows whether audio is present only part of the time.
        val perSec = (0 until n / RATE).joinToString(",") { s ->
            var q = 0.0
            for (i in s * RATE until (s + 1) * RATE) q += pcm[i].toDouble() * pcm[i]
            "%.0f".format(sqrt(q / RATE))
        }
        val first2 = minOf(n, RATE * 2)
        var q2 = 0.0
        for (i in 0 until first2) q2 += pcm[i].toDouble() * pcm[i]
        val am = ctx.getSystemService(AudioManager::class.java)
        SpikeLog.log("S2", "$tag rmsFirst2s=%.1f speakerphone=${am.isSpeakerphoneOn} audioMode=${modeName(am.mode)}".format(sqrt(q2 / first2)))
        val file = writeWav(ctx, name, pcm, n)
        SpikeLog.log("S2", "$tag samples=$n rms=%.1f dbfs=%.1f peak=$peak zeroFraction=%.3f perSecondRms=[$perSec] file=${file?.name}"
            .format(rms, dbfs, zeros.toDouble() / n))
    }

    private fun writeWav(ctx: Context, name: String, pcm: ShortArray, n: Int): File? = try {
        val dir = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).let { File(it, "s2").apply { mkdirs() } }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "s2-$name-$stamp.wav")
        val dataLen = n * 2
        RandomAccessFile(f, "rw").use { raf ->
            val h = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
                .putShort(2).putShort(16)
            h.put("data".toByteArray()).putInt(dataLen)
            for (i in 0 until n) h.putShort(pcm[i])
            raf.write(h.array())
        }
        f
    } catch (e: Throwable) {
        SpikeLog.log("S2", "WAV write failed: $e"); null
    }

    private fun modeName(m: Int) = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> m.toString()
    }
}
