package com.callanswer.app.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.os.SystemClock
import com.callanswer.app.CallLog
import com.callanswer.app.audio.AudioRecordCallAudioCapture
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Minimal accessibility service. It handles no events and reads no screen content. It exists because only a
 * process that holds a bound accessibility service receives the Caller's audio during a call (spike S2c).
 * [A11yToggle] switches it on when a call becomes active and off when the call ends; it is never left on.
 */
class CallAudioA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectedAt = SystemClock.elapsedRealtime()
        A11yToggle.onConnected(this)
    }

    @Volatile private var connectedAt = 0L
    private var executor: ExecutorService? = null

    /**
     * Starts the capture's read loop on a thread owned by this service (as in spike S2c, where the `AudioRecord`
     * was created and read on a thread the accessibility service started), no earlier than [CAPTURE_DELAY_MS]
     * after [onServiceConnected]. Frames reach the pipeline through the capture's `frames` flow.
     */
    fun startCapture(build: (kotlinx.coroutines.CoroutineDispatcher) -> AudioRecordCallAudioCapture): AudioRecordCallAudioCapture? {
        val ex = synchronized(this) {
            executor ?: Executors.newSingleThreadExecutor { r -> Thread(r, "call-audio-a11y") }.also { executor = it }
        }
        val capture = build(ex.asCoroutineDispatcher())
        val wait = (CAPTURE_DELAY_MS - (SystemClock.elapsedRealtime() - connectedAt)).coerceAtLeast(0L)
        CallLog.i("a11y: starting AudioRecord loop on service thread in $wait ms")
        return if (capture.start(wait)) capture else null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        CallLog.i("a11y: service unbound")
        if (instance === this) instance = null
        synchronized(this) { executor?.shutdown(); executor = null }
        A11yToggle.onDisconnected()
        return super.onUnbind(intent)
    }

    companion object {
        /** The spike started capture ~3.4 s after connect and had audio; 717 ms was too early. */
        const val CAPTURE_DELAY_MS = 1_500L
        /** The connected service, or null. Its context is preferred for the call's `AudioRecord`. */
        @Volatile var instance: CallAudioA11yService? = null
            private set
    }
}
