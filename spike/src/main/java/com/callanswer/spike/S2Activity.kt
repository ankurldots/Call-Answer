package com.callanswer.spike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * S2 screen. Choose the owner (accessibility service or plain foreground service), a start delay
 * (time to place a call and put it on the loudspeaker) and seconds per source, then run.
 */
class S2Activity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        title = "S2 Microphone capture"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            textSize = 14f
            text = "Enable the accessibility service and grant RECORD_AUDIO first. Run once with no call (baseline), " +
                "then start a call with the second phone, put it on the loudspeaker and run again. " +
                "Each of 4 sources is captured in turn; a WAV per source goes to app storage (s2/) and the " +
                "RMS summary and isClientSilenced() go to the log. Repeat with the foreground service box ticked " +
                "to compare: a silent result there and audible result in the accessibility run is the expected pass."
        })
        val fgs = CheckBox(this).apply { text = "Capture from plain foreground service instead" }
        val delay = EditText(this).apply { hint = "Start delay, seconds"; setText("10"); inputType = 2 }
        val secs = EditText(this).apply { hint = "Seconds per source"; setText("10"); inputType = 2 }
        root.addView(fgs); root.addView(delay); root.addView(secs)
        root.addView(Button(this).apply {
            text = "Start S2 capture"
            isAllCaps = false
            setOnClickListener {
                val d = delay.text.toString().toIntOrNull() ?: 0
                val s = (secs.text.toString().toIntOrNull() ?: 10).coerceIn(1, 60)
                start(fgs.isChecked, d, s)
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start(useForegroundService: Boolean, delaySec: Int, seconds: Int) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant RECORD_AUDIO on the main screen", Toast.LENGTH_LONG).show(); return
        }
        if (useForegroundService) {
            startForegroundService(
                Intent(this, S2CaptureService::class.java)
                    .putExtra(S2CaptureService.EXTRA_SECONDS, seconds)
                    .putExtra(S2CaptureService.EXTRA_DELAY, delaySec)
            )
        } else {
            val svc = S2Bridge.accessibilityContext
            if (svc == null) {
                Toast.makeText(this, "Accessibility service is not connected", Toast.LENGTH_LONG).show(); return
            }
            Thread {
                Thread.sleep(delaySec * 1000L)
                S2Capture.runAll(svc, S2Owner.ACCESSIBILITY_SERVICE, seconds)
            }.start()
        }
        Toast.makeText(this, "Started. Place the call now. Results in the log.", Toast.LENGTH_LONG).show()
    }
}
