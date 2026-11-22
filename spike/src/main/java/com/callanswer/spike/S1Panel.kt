package com.callanswer.spike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** S1 screen content: instructions, permission request, manual foreground-service start and end-call buttons. */
object S1Panel {
    fun build(activity: Activity, root: LinearLayout, body: TextView) {
        body.text = "Make the spike the call screening app (role request on the main menu), grant the permissions, " +
            "then call this phone from another number. The call is answered, a foreground service starts, " +
            "and the call is ended after ${S1Spike.AUTO_END_DELAY_MS / 1000} s. Results are in the spike log, tag S1."
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, root.childCount - 1)
        }
        button("Request phone and notification permissions") {
            activity.requestPermissions(
                arrayOf(
                    Manifest.permission.ANSWER_PHONE_CALLS,
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
                101,
            )
        }
        button("Start foreground service now") {
            S1Spike.markRing()
            S1Spike.startForeground(activity.applicationContext)
        }
        button("End call now") { S1Spike.endCall(activity, "manual button") }
        if (activity.intent.getBooleanExtra(S1Spike.EXTRA_S1_START_FGS, false)) {
            // Launched by the full-screen-intent fallback: the activity is visible, so a start is allowed.
            SpikeLog.log(S1Spike.CHECK, "fallback activity visible at sinceRing=${S1Spike.sinceRing()} ms; starting foreground service")
            activity.startForegroundService(Intent(activity, SpikeForegroundService::class.java))
        }
    }
}
