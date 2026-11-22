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
import android.util.Log
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Microphone capture during a call. [silenced] is true when the platform delivers silence to this client. */
interface CallAudioCapture {
    val frames: Flow<PcmFrame>
    val silenced: StateFlow<Boolean>
}

/**
 * `AudioRecord` capture, 16 kHz mono PCM16. Must be created and started by the
 * `CallAudioAccessibilityService`: on Android 10+ only an accessibility service keeps receiving audio
 * while the call holds `MODE_IN_CALL`.
 *
 * [sources] is the order tried at runtime (design P-AUDIO step 2); the first one that initialises is used.
 * Put the source chosen in spike S2 first to fix it.
 */
class AudioRecordCallAudioCapture(
    private val audioManager: AudioManager,
    private val sources: List<Int> = DEFAULT_SOURCES,
) : CallAudioCapture {
    private val _frames = MutableSharedFlow<PcmFrame>(extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _silenced = MutableStateFlow(false)
    override val frames: Flow<PcmFrame> = _frames.asSharedFlow()
    override val silenced: StateFlow<Boolean> = _silenced.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var activeSource: Int = -1

    /** The `MediaRecorder.AudioSource` in use, or -1 when not capturing. */
    val source: Int get() = activeSource

    /** Starts capture. Returns false when no source could be initialised. Safe to call twice. */
    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (job?.isActive == true) return true
        val rec = buildRecord() ?: return false
        val thread = HandlerThread("call-audio-cb").also { it.start() }
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId }
                    ?.let { _silenced.value = it.isClientSilenced }
            }
        }
        audioManager.registerAudioRecordingCallback(callback, Handler(thread.looper))
        job = scope.launch {
            try {
                rec.startRecording()
                rec.activeRecordingConfiguration?.let { _silenced.value = it.isClientSilenced }
                val buf = ShortArray(FRAME_SAMPLES)
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) { Log.w(TAG, "read error $n"); break }
                    if (n > 0) _frames.tryEmit(PcmFrame(buf.copyOf(n), SystemClock.elapsedRealtime()))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "capture failed", t)
            } finally {
                try { rec.stop() } catch (_: Throwable) {}
                rec.release()
                audioManager.unregisterAudioRecordingCallback(callback)
                thread.quitSafely()
                activeSource = -1
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        _silenced.value = false
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        for (src in sources) {
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
                Log.w(TAG, "source $src build failed", t); null
            }
            if (rec == null) continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) { activeSource = src; return rec }
            rec.release()
        }
        return null
    }

    companion object {
        const val RATE = 16_000
        /** 512 samples = 32 ms, one Silero VAD window. */
        const val FRAME_SAMPLES = 512
        private const val TAG = "CallAudioCapture"
        val DEFAULT_SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
    }
}
