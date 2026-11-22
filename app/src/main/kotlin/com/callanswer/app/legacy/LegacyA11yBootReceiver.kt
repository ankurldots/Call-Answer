package com.callanswer.app.legacy
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
/** After a reboot, remove the old accessibility service entry if the archived build left it enabled. */
class LegacyA11yBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) LegacyA11yCleanup.run(context, "boot")
    }
}
