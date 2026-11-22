package com.callanswer.spike

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** S2c: minimal accessibility service. It exists so the AudioRecord is owned by an accessibility service; it handles no events. */
class SpikeCallAudioA11yService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        SpikeLog.init(this)
        instance = this
        A11yToggle.onConnected(this)
        S2cFlow.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        SpikeLog.log("S2c", "a11y service unbound")
        instance = null
        A11yToggle.onDisconnected()
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: SpikeCallAudioA11yService? = null
    }
}
