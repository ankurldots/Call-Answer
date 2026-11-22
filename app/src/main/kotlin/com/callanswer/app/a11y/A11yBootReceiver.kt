package com.callanswer.app.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot our accessibility service must be off unless a call is active. */
class A11yBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) A11yToggle.startupCheck(context, "boot")
    }
}
