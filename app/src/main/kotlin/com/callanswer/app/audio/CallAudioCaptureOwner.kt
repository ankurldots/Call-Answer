package com.callanswer.app.audio

import android.content.Context
import com.callanswer.app.CallLog
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns the call-audio `AudioRecord` for the current call. It lives in the process of the
 * `CallForegroundService`, which must be in the foreground with service type `microphone` for the capture to
 * receive audio. [start] is called by the call pipeline and [stop] when the call ends.
 *
 * When capture is silenced by the platform ([AudioRecordCallAudioCapture.silenced]) or delivers only zero
 * samples for [ZERO_RUN_FRAMES] frames, [assistedMode] turns on (fallback F1: speak-only) and the User is
 * notified once per call.
 */
object CallAudioCaptureOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var capture: AudioRecordCallAudioCapture? = null
    private var watchers = mutableListOf<Job>()

    private val _assistedMode = MutableStateFlow(false)

    /** True when capture is silenced or all-zero and the Agent works in speak-only assisted mode (F1). */
    val assistedMode: StateFlow<Boolean> = _assistedMode.asStateFlow()

    /** Starts capture for a call. Returns the capture, or null when no audio source could be opened. */
    @Synchronized
    fun start(context: Context, notifier: () -> Unit): CallAudioCapture? {
        capture?.let { return it }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val c = AudioRecordCallAudioCapture(audio)
        _assistedMode.value = false
        if (!c.start()) {
            CallLog.w("capture owner: AudioRecord could not start, assisted mode")
            _assistedMode.value = true
            watchers += scope.launch { notifier() }
            return null
        }
        capture = c
        val silenced = MutableStateFlow(false)
        failed.value = false
        watchers += scope.launch { c.silenced.collect { silenced.value = it; refresh(silenced.value, zero.value) } }
        watchers += scope.launch { c.failed.collect { failed.value = it; refresh(silenced.value, zero.value) } }
        watchers += scope.launch {
            var run = 0
            c.frames.collect { f ->
                run = if (f.samples.all { it.toInt() == 0 }) run + 1 else 0
                zero.value = run >= ZERO_RUN_FRAMES
                refresh(silenced.value, zero.value)
            }
        }
        watchers += scope.launch {
            _assistedMode.first { it }
            notifier()
        }
        return c
    }

    private val zero = MutableStateFlow(false)
    private val failed = MutableStateFlow(false)

    private fun refresh(silenced: Boolean, allZero: Boolean) {
        val v = silenced || allZero || failed.value
        if (_assistedMode.value != v) CallLog.i("assisted mode=$v (silenced=$silenced allZero=$allZero failed=${failed.value})")
        _assistedMode.value = v
    }

    /**
     * Tears down and recreates the `AudioRecord`: a silenced one does not recover by itself once the
     * microphone foreground type has been granted.
     */
    fun restart() {
        val c = synchronized(this) { capture } ?: return
        CallLog.i("capture owner: restarting AudioRecord")
        scope.launch {
            c.stopAndJoin()
            zero.value = false
            synchronized(this@CallAudioCaptureOwner) { if (capture === c) c.start() }
        }
    }

    @Synchronized
    fun stop() {
        watchers.forEach { it.cancel() }
        watchers.clear()
        capture?.stop()
        capture = null
        zero.value = false
        _assistedMode.value = false
    }

    /** About 3.2 s of consecutive all-zero frames (512 samples at 16 kHz = 32 ms each). */
    const val ZERO_RUN_FRAMES = 100
}
