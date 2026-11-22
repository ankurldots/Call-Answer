package com.callanswer.spike

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * S2 screen. The AudioRecord is owned by a plain foreground service (microphone type). Choose a start delay
 * (time to place a call and put it on the loudspeaker) and seconds per source, then run.
 */
class S2Activity : Activity() {
    private lateinit var banner: TextView

    override fun onResume() {
        super.onResume()
        banner.text = S1Settings.bannerText(this)
        banner.setBackgroundColor(if (S1Settings.mode(this) == S1Mode.AUTO_ANSWER_AND_END) 0xFFFFCDD2.toInt() else 0xFFC8E6C9.toInt())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        title = "S2 Microphone capture"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        banner = TextView(this).apply { textSize = 14f; setPadding(16, 16, 16, 16) }
        root.addView(banner)
        root.addView(TextView(this).apply {
            textSize = 14f
            text = "Grant RECORD_AUDIO first. Run once with no call (baseline), " +
                "then start a call with the second phone, put it on the loudspeaker and run again. " +
                "Each of 4 sources is captured in turn; a WAV per source goes to app storage (s2/) and the " +
                "RMS summary and isClientSilenced() go to the log. Pass: VOICE_RECOGNITION or VOICE_COMMUNICATION " +
                "(speakerphone on) records the Caller audibly. If every source is silent, see the S2 fallbacks in the design."
        })
        val delay = EditText(this).apply { hint = "Start delay, seconds"; setText("10"); inputType = 2 }
        val secs = EditText(this).apply { hint = "Seconds per source"; setText("10"); inputType = 2 }
        val secsOptions = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("Seconds per source: 10 (default)", "Seconds per source: 30"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) { secs.setText(if (pos == 0) "10" else "30") }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        val sourceNames = listOf("ALL sources") + S2Capture.SOURCES.map { it.first }
        val source = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, sourceNames.map { "Source: $it" })
        }
        val offhook = CheckBox(this).apply { text = "Start capture when the call becomes OFFHOOK (ignores the delay; waits up to 120 s)" }
        root.addView(source); root.addView(secsOptions); root.addView(offhook); root.addView(delay); root.addView(secs)
        root.addView(Button(this).apply {
            text = "Start S2 capture"
            isAllCaps = false
            setOnClickListener {
                val d = delay.text.toString().toIntOrNull() ?: 0
                val s = (secs.text.toString().toIntOrNull() ?: 10).coerceIn(1, 60)
                val only = if (source.selectedItemPosition == 0) null else sourceNames[source.selectedItemPosition]
                start(d, s, only, offhook.isChecked)
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun start(delaySec: Int, seconds: Int, only: String?, waitOffhook: Boolean) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Grant RECORD_AUDIO on the main screen", Toast.LENGTH_LONG).show(); return
        }
        startForegroundService(
            Intent(this, S2CaptureService::class.java)
                .putExtra(S2CaptureService.EXTRA_SECONDS, seconds)
                .putExtra(S2CaptureService.EXTRA_DELAY, delaySec)
                .putExtra(S2CaptureService.EXTRA_SOURCE, only)
                .putExtra(S2CaptureService.EXTRA_WAIT_OFFHOOK, waitOffhook)
        )
        Toast.makeText(this, "Started. Place the call now. Results in the log.", Toast.LENGTH_LONG).show()
    }
}
