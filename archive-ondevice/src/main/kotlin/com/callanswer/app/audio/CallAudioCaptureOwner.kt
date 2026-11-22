package com.callanswer.app.audio

import android.content.Context
import com.callanswer.app.CallLog
import com.callanswer.app.a11y.CallAudioA11yService
import android.media.AudioManager
import android.os.SystemClock
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
 * Owns the call-audio `AudioRecord` for the current call. While the accessibility service
 * ([CallAudioA11yService], enabled only during the call) is connected, the record is created with its context
 * (spike S2c: only then the Caller's audio arrives); otherwise it falls back to the `CallForegroundService`
 * context, which must hold service type `microphone`. [start] is called by the call pipeline and [stop] when the
 * call ends.
 *
 * Late silencing: when the accessibility-owned record returns zeros or is silenced after it had audio, it is
 * recreated once, and after 10 s of silence the Agent goes to assisted mode ([LateSilenceTracker]).
 *
 * When capture is silenced by the platform ([AudioRecordCallAudioCapture.silenced]) or delivers only zero
 * samples for [ZERO_RUN_FRAMES] frames, [assistedMode] turns on (fallback F1: speak-only) and the User is
 * notified once per call.
 */
object CallAudioCaptureOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var capture: AudioRecordCallAudioCapture? = null
    private var watchers = mutableListOf<Job>()
    private var a11yOwned = false
    private var tracker = LateSilenceTracker()
    private var storedNotifier: (() -> Unit)? = null
    @Volatile private var giveUp = false
    @Volatile private var heardAudio = false

    private val _assistedMode = MutableStateFlow(false)

    /** True when capture is silenced or all-zero and the Agent works in speak-only assisted mode (F1). */
    val assistedMode: StateFlow<Boolean> = _assistedMode.asStateFlow()

    /** Starts capture for a call. Returns the capture, or null when no audio source could be opened. */
    @Synchronized
    fun start(context: Context, notifier: () -> Unit): CallAudioCapture? {
        capture?.let { return it }
        val a11y = CallAudioA11yService.instance
        a11yOwned = a11y != null
        val owner: Context = a11y ?: context
        CallLog.i("capture owner: AudioRecord owner=${if (a11y != null) "accessibility service" else "foreground service"}")
        storedNotifier = notifier
        tracker = LateSilenceTracker()
        giveUp = false
        heardAudio = false
        val audio = owner.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        _assistedMode.value = false
        val c: AudioRecordCallAudioCapture?
        if (a11y != null) {
            c = a11y.startCapture { d -> AudioRecordCallAudioCapture(audio, dispatcher = d) }
        } else {
            c = AudioRecordCallAudioCapture(audio).takeIf { it.start() }
        }
        if (c == null) {
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
                val nonZero = f.samples.any { it.toInt() != 0 }
                if (nonZero) heardAudio = true
                run = if (!nonZero) run + 1 else 0
                zero.value = run >= ZERO_RUN_FRAMES
                if (a11yOwned) lateSilence(c, tracker.onFrame(SystemClock.elapsedRealtime(), nonZero, silenced.value), nonZero)
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

    private fun lateSilence(c: AudioRecordCallAudioCapture, action: LateSilenceTracker.Action, nonZero: Boolean) {
        if (nonZero && giveUp) { giveUp = false; CallLog.i("capture owner: audio is back") }
        when (action) {
            LateSilenceTracker.Action.RESTART -> {
                CallLog.i("capture owner: a11y AudioRecord silent after audio, recreating it once")
                restart()
            }
            LateSilenceTracker.Action.ASSISTED -> {
                CallLog.w("capture owner: a11y AudioRecord silent for 10 s, assisted mode")
                giveUp = true
            }
            LateSilenceTracker.Action.NONE -> Unit
        }
    }

    private fun refresh(silenced: Boolean, allZero: Boolean) {
        // After audio had flowed in the accessibility-owned record, only the tracker (restart, then 10 s) decides.
        val soft = (silenced || allZero) && !(a11yOwned && tracker.hadAudio)
        val v = soft || giveUp || failed.value
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

    /**
     * The accessibility service did not bind within 5 s of being enabled. Unless the foreground-service capture
     * already hears the Caller, the Agent switches to assisted mode.
     */
    fun onA11yBindTimeout() {
        if (heardAudio) { CallLog.i("capture owner: a11y bind timeout but audio is flowing, no assisted mode"); return }
        CallLog.w("capture owner: a11y bind timeout, assisted mode")
        giveUp = true
        _assistedMode.value = true
        storedNotifier?.let { n -> scope.launch { n() } }
    }

    @Synchronized
    fun stop() {
        watchers.forEach { it.cancel() }
        watchers.clear()
        capture?.stop()
        capture = null
        zero.value = false
        giveUp = false
        heardAudio = false
        a11yOwned = false
        _assistedMode.value = false
    }

    /** About 3.2 s of consecutive all-zero frames (512 samples at 16 kHz = 32 ms each). */
    const val ZERO_RUN_FRAMES = 100
}
