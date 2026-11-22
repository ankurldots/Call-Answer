package com.callanswer.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.callanswer.app.CallLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Microphone capture during a call. [silenced] is true when the platform delivers silence to this client. */
interface CallAudioCapture {
    val frames: Flow<PcmFrame>
    val silenced: StateFlow<Boolean>
}

/**
 * `AudioRecord` capture, 16 kHz mono PCM16. Owned by [CallAudioCaptureOwner], which runs inside the
 * `CallForegroundService` (foreground service type `microphone`). Whether the platform delivers the Caller's
 * voice while the call holds `MODE_IN_CALL` without an accessibility service is the open question of spike S2;
 * a silenced or all-zero capture switches the Agent to assisted mode (fallback F1).
 *
 * [sources] is the order tried at runtime (design P-AUDIO step 2); the first one that initialises is used.
 * Put the source chosen in spike S2 first to fix it.
 */
class AudioRecordCallAudioCapture(
    private val audioManager: AudioManager,
    private val sources: List<Int> = DEFAULT_SOURCES,
    /** Runs the blocking read loop. The accessibility service passes a dispatcher backed by its own thread. */
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : CallAudioCapture {
    private val _frames = MutableSharedFlow<PcmFrame>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _silenced = MutableStateFlow(false)
    override val frames: Flow<PcmFrame> = _frames.asSharedFlow()
    override val silenced: StateFlow<Boolean> = _silenced.asStateFlow()
    private val _failed = MutableStateFlow(false)

    /** True when no audio source could be opened or started (the `AudioRecord` failed). */
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    @Volatile private var activeSource: Int = -1

    /** The `MediaRecorder.AudioSource` in use, or -1 when not capturing. */
    val source: Int get() = activeSource

    /**
     * Starts capture. Returns false when no source could be opened at all. Safe to call twice. Sources are tried
     * in order; a source that is silenced or delivers only zero samples for [PROBE_MS] is released and the next
     * one is tried (cycling), until one delivers non-zero audio. Frames of every source are emitted.
     */
    @Synchronized
    fun start(initialDelayMs: Long = 0L): Boolean {
        if (job?.isActive == true) return true
        if (AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) <= 0) {
            CallLog.w("capture: getMinBufferSize invalid")
            return false
        }
        logAudioState("capture start")
        _failed.value = false
        job = scope.launch {
            try {
                if (initialDelayMs > 0) kotlinx.coroutines.delay(initialDelayMs)
                var idx = 0
                var failedInARow = 0
                while (isActive) {
                    val src = sources[idx % sources.size]
                    idx++
                    val keep = runSource(src)
                    if (keep == SourceResult.OPEN_FAILED) {
                        failedInARow++
                        if (failedInARow >= sources.size) { CallLog.w("capture: no source could be opened"); _failed.value = true; break }
                    } else {
                        failedInARow = 0
                    }
                    if (keep == SourceResult.ENDED) break
                }
            } catch (t: Throwable) {
                CallLog.w("capture failed", t)
            } finally {
                activeSource = -1
            }
        }
        return true
    }

    private enum class SourceResult { OPEN_FAILED, ROTATE, ENDED }

    /** Runs one source until the job is cancelled, or until it is judged dead in its first [PROBE_MS]. */
    @SuppressLint("MissingPermission")
    private suspend fun runSource(src: Int): SourceResult {
        val name = sourceName(src)
        val rec = buildRecord(src) ?: return SourceResult.OPEN_FAILED
        val thread = HandlerThread("call-audio-cb").also { it.start() }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }?.let {
                    if (_silenced.value != it.isClientSilenced) CallLog.i("capture $name: isClientSilenced=${it.isClientSilenced}")
                    _silenced.value = it.isClientSilenced
                }
            }
        }
        try {
            audioManager.registerAudioRecordingCallback(callback, Handler(thread.looper))
            try {
                rec.startRecording()
            } catch (t: Throwable) {
                CallLog.w("capture $name: startRecording threw", t)
                return SourceResult.OPEN_FAILED
            }
            CallLog.i("capture $name: startRecording recordingState=${rec.recordingState} (3=RECORDING) " +
                "silencedNow=${rec.activeRecordingConfiguration?.isClientSilenced}")
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return SourceResult.OPEN_FAILED
            activeSource = src
            rec.activeRecordingConfiguration?.let { _silenced.value = it.isClientSilenced }
            val buf = ShortArray(FRAME_SAMPLES)
            val probeStart = SystemClock.elapsedRealtime()
            var probing = true
            var sumSq = 0.0
            var count = 0L
            var anyNonZero = false
            var peak = 0
            var wSumSq = 0.0
            var wCount = 0L
            var wPeak = 0
            var wStart = probeStart
            while (currentCoroutineContext().isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) { CallLog.w("capture $name: read error $n"); return SourceResult.ROTATE }
                if (n == 0) continue
                _frames.tryEmit(PcmFrame(buf.copyOf(n), SystemClock.elapsedRealtime()))
                val now = SystemClock.elapsedRealtime()
                for (i in 0 until n) {
                    val a = Math.abs(buf[i].toInt())
                    wSumSq += a.toDouble() * a; if (a > wPeak) wPeak = a
                }
                wCount += n
                if (now - wStart >= LOG_MS) {
                    CallLog.i("capture $name: rms=${"%.1f".format(Math.sqrt(wSumSq / wCount))} peak=$wPeak " +
                        "silenced=${_silenced.value}")
                    wSumSq = 0.0; wCount = 0; wPeak = 0; wStart = now
                }
                if (probing) {
                    for (i in 0 until n) {
                        val v = buf[i].toDouble(); sumSq += v * v; if (buf[i].toInt() != 0) anyNonZero = true
                        val a = Math.abs(buf[i].toInt()); if (a > peak) peak = a
                    }
                    count += n
                    if (SystemClock.elapsedRealtime() - probeStart >= PROBE_MS) {
                        probing = false
                        val rms = if (count > 0) Math.sqrt(sumSq / count) else 0.0
                        val silencedNow = rec.activeRecordingConfiguration?.isClientSilenced ?: _silenced.value
                        val usable = AudioActivity.isUsable(rms, peak)
                        CallLog.i("capture $name: first ${PROBE_MS}ms rms=${"%.1f".format(rms)} peak=$peak nonZero=$anyNonZero usable=$usable " +
                            "silenced=$silencedNow audioMode=${modeName(audioManager.mode)} speaker=${speakerOn()}")
                        if (!usable || silencedNow) {
                            CallLog.i("capture $name: no usable audio, rotating to next source")
                            return SourceResult.ROTATE
                        }
                        CallLog.i("capture: source $name delivers audio, keeping it")
                    }
                }
            }
            return SourceResult.ENDED
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
            runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
            thread.quitSafely()
        }
    }

    /** Cancels capture and waits until the `AudioRecord` is released. */
    suspend fun stopAndJoin() {
        val j = synchronized(this) { job.also { job = null } }
        j?.cancelAndJoin()
        _silenced.value = false
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _silenced.value = false
    }

    @Suppress("DEPRECATION")
    private fun speakerOn() = audioManager.isSpeakerphoneOn

    @SuppressLint("MissingPermission")
    private fun buildRecord(src: Int): AudioRecord? {
        val name = sourceName(src)
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES * 2) * 4)
                .build()
        } catch (t: Throwable) {
            CallLog.w("capture $name: build failed", t); return null
        }
        CallLog.i("capture $name: AudioRecord state=${rec.state} (1=INITIALIZED)")
        if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
        rec.release()
        return null
    }

    private fun logAudioState(what: String) {
        CallLog.i("$what: audioMode=${modeName(audioManager.mode)} speaker=${speakerOn()} sources=${sources.map { sourceName(it) }}")
    }

    companion object {
        const val RATE = 16_000
        /** 512 samples = 32 ms, one Silero VAD window. */
        const val FRAME_SAMPLES = 512
        /** Time one source gets to deliver non-zero, non-silenced audio before the next one is tried. */
        const val PROBE_MS = 2_000L
        /** Interval of the RMS log line for the whole call. */
        const val LOG_MS = 2_000L
        fun modeName(mode: Int) = when (mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> "mode$mode"
        }
        fun sourceName(src: Int) = when (src) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            else -> "src$src"
        }
        val DEFAULT_SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
        )
    }
}

/** Decides whether a source delivers real audio rather than near-silence (dither, comfort noise). */
object AudioActivity {
    const val MIN_RMS = 30.0
    const val MIN_PEAK = 300

    /** True when the 2 s window has RMS >= [MIN_RMS] or a peak >= [MIN_PEAK]. */
    fun isUsable(rms: Double, peak: Int): Boolean = rms >= MIN_RMS || peak >= MIN_PEAK
}
