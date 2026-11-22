package com.callanswer.spike

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** S7 screen: model check, cold pass over all engines, and 10 minute sustained loop per engine. */
class S7Activity : Activity() {
    private lateinit var out: TextView
    private var bench: S7Bench? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        title = "S7 On-device AI benchmark"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        out = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(btn("Check model files") { start { b -> b.modelReport().forEach { say(it) } } })
        root.addView(btn("Cold pass: all engines (30 utterances each)") { start { b -> b.run(0L, null) } })
        root.addView(btn("Sustained 10 min: Whisper base int8") { start { b -> b.run(SUSTAINED_MS, "whisper-base-int8") } })
        root.addView(btn("Sustained 10 min: Vosk small hi") { start { b -> b.run(SUSTAINED_MS, "vosk-small-hi") } })
        root.addView(btn("Sustained 10 min: Vosk small en-in") { start { b -> b.run(SUSTAINED_MS, "vosk-small-en-in") } })
        root.addView(btn("Cancel") { bench?.cancelled = true })
        root.addView(out)
        setContentView(ScrollView(this).apply { addView(root) })
        say("Models go in ${S7Models(this).root.absolutePath} (see spike/S7-README.md).")
    }

    private fun start(block: (S7Bench) -> Unit) {
        if (running) { say("already running"); return }
        running = true
        val b = S7Bench(this) { say(it) }
        bench = b
        Thread({
            try {
                block(b)
            } catch (t: Throwable) {
                say("S7 failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running = false
            }
        }, "s7-bench").start()
    }

    private fun say(line: String) {
        SpikeLog.log("S7", line)
        runOnUiThread { out.append(line + "\n") }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    companion object {
        private const val SUSTAINED_MS = 10 * 60 * 1000L
    }
}
