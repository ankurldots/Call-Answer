package com.callanswer.spike

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Startup check: our accessibility service must not stay on outside a call. */
class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        A11yToggle.startupCheck(this, "app start")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) A11yToggle.startupCheck(context, "boot")
    }
}
