package com.callanswer.spike

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Placeholder. Task 1.3 adds AudioRecord capture (S2); task 1.4 adds the Speaker click (S3). */
class SpikeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        SpikeLog.init(this)
        SpikeLog.log("S2", "AccessibilityService connected")
        S2Bridge.accessibilityContext = this
    }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        S2Bridge.accessibilityContext = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
