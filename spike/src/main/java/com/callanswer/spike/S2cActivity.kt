package com.callanswer.spike

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** S2c screen: accessibility only during the call. Arm, then call the phone; the flow runs by itself. */
class S2cActivity : Activity() {
    private lateinit var banner: TextView
    private lateinit var state: TextView

    override fun onResume() {
        super.onResume()
        banner.text = S1Settings.bannerText(this)
        banner.setBackgroundColor(if (S1Settings.mode(this) == S1Mode.AUTO_ANSWER_ONLY) 0xFFC8E6C9.toInt() else 0xFFFFCDD2.toInt())
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        title = "S2c Accessibility only during the call"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        banner = TextView(this).apply { textSize = 14f; setPadding(16, 16, 16, 16) }
        root.addView(banner)
        root.addView(TextView(this).apply {
            textSize = 14f
            text = "Grant once via adb (USB debugging, phone connected):\n\n${A11yToggle.GRANT_COMMAND}\n\n" +
                "Set S1 to AUTO_ANSWER_ONLY and keep the call screening role on this app. Tap Arm, then call this phone from " +
                "the second phone and speak for 40 s. On OFFHOOK the app turns its accessibility service on, switches the " +
                "speakerphone on, captures (VOICE_RECOGNITION first) and turns the service off when the call ends."
        })
        root.addView(Button(this).apply {
            text = "Copy adb grant command"
            isAllCaps = false
            setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", A11yToggle.GRANT_COMMAND))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        })
        val source = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, S2cFlow.SOURCE_CHOICES.map { "Source: $it" })
            setSelection(1) // VOICE_RECOGNITION
        }
        root.addView(source)
        root.addView(Button(this).apply {
            text = "Arm S2c (wait for call)"
            isAllCaps = false
            setOnClickListener {
                val s = S2cFlow.SOURCE_CHOICES[source.selectedItemPosition].let { if (source.selectedItemPosition == 0) null else it }
                val err = S2cFlow.arm(context, s, 10)
                Toast.makeText(context, err ?: "Armed. Call this phone now.", Toast.LENGTH_LONG).show()
                refresh()
            }
        })
        root.addView(Button(this).apply {
            text = "Disarm and force accessibility OFF"
            isAllCaps = false
            setOnClickListener {
                S2cFlow.disarm("button")
                A11yToggle.disable(context, "manual button")
                refresh()
            }
        })
        root.addView(Button(this).apply {
            text = "Open App info (Allow restricted settings)"
            isAllCaps = false
            setOnClickListener { startActivity(A11yToggle.appInfoIntent(context)) }
        })
        state = TextView(this).apply { textSize = 13f; setPadding(0, 16, 0, 0) }
        root.addView(state)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refresh() {
        val lat = A11yToggle.lastLatencyMs(this)
        state.text = "WRITE_SECURE_SETTINGS granted: ${if (A11yToggle.hasPermission(this)) "YES" else "NO"}\n" +
            "our accessibility service enabled now: ${if (A11yToggle.isEnabled(this)) "YES (should be NO outside calls)" else "NO"}\n" +
            "last enable-to-connected latency: ${if (lat >= 0) "$lat ms" else "none yet"}\n" +
            "armed: ${S2cFlow.armed}\n" +
            (if (A11yToggle.restrictedHint(this)) "Last enable did not bind in 5 s. Open App info > menu > Allow restricted settings.\n" else "")
    }
}
