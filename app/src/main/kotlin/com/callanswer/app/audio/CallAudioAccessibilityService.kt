package com.callanswer.app.audio

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.view.accessibility.AccessibilityEvent
import com.callanswer.app.service.NotificationCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Enabled once by the User. Owns the `AudioRecord` (the accessibility-service exemption lets it receive
 * audio during a call) and exposes the Samsung in-call UI automator. Bound by the system, so it is not
 * subject to background-start limits.
 *
 * Capture is started per call with [startCapture] and stopped with [stopCapture]. When capture is
 * silenced the service sets [assistedMode] (fallback F1: speak-only) and alerts the User.
 */
class CallAudioAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: AudioRecordCallAudioCapture? = null
    val uiAutomator = InCallUiAutomator { rootInActiveWindow }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    /** Starts capture for a call. Returns the capture, or null when no audio source could be opened. */
    fun startCapture(): CallAudioCapture? {
        capture?.let { return it }
        val c = AudioRecordCallAudioCapture(getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        if (!c.start()) return null
        capture = c
        _assistedMode.value = false
        scope.launch {
            c.silenced.first { it }
            _assistedMode.value = true
            NotificationCenter(this@CallAudioAccessibilityService).captureSilenced()
        }
        return c
    }

    fun stopCapture() {
        capture?.stop()
        capture = null
        _assistedMode.value = false
    }

    // The service reads window content only on demand through rootInActiveWindow.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopCapture()
        _instance.value = null
        scope.cancel()
        return super.onUnbind(intent)
    }

    companion object {
        private val _instance = MutableStateFlow<CallAudioAccessibilityService?>(null)
        /** The connected service, or null when the User has not enabled it. */
        val instance: StateFlow<CallAudioAccessibilityService?> = _instance.asStateFlow()

        private val _assistedMode = MutableStateFlow(false)
        /** True when capture is silenced and the Agent works in speak-only assisted mode (F1). */
        val assistedMode: StateFlow<Boolean> = _assistedMode.asStateFlow()
    }
}
