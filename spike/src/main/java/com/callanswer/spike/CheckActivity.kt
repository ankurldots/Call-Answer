package com.callanswer.spike

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Host screen for one check. S0 is implemented here; S1 to S8 are filled in by tasks 1.2 to 1.9. */
class CheckActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        val check = SpikeCheck.values().firstOrNull { it.id == intent.getStringExtra(EXTRA_CHECK) } ?: SpikeCheck.S0
        title = "${check.id} ${check.title}"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val body = TextView(this).apply { textSize = 14f }
        root.addView(body)

        if (check == SpikeCheck.S0) {
            val run = Button(this).apply {
                text = "Run S0 and write to log"
                isAllCaps = false
                setOnClickListener { body.text = render(S0Readout.logAll(this@CheckActivity)) }
            }
            root.addView(run, 0)
            body.text = render(S0Readout.collect(this))
        } else if (check == SpikeCheck.S1) {
            S1Panel.build(this, root, body)
        } else if (check == SpikeCheck.S2) {
            body.text = "S2 opens its own screen."
            startActivity(android.content.Intent(this, S2Activity::class.java))
            finish()
        } else if (check == SpikeCheck.S2B) {
            startActivity(android.content.Intent(this, S2bActivity::class.java))
            finish()
            return
        } else if (check == SpikeCheck.S2C) {
            startActivity(android.content.Intent(this, S2cActivity::class.java))
            finish()
            return
        } else if (check == SpikeCheck.S3) {
            startActivity(android.content.Intent(this, S3Activity::class.java))
            finish()
            return
        } else if (check == SpikeCheck.S4) {
            startActivity(android.content.Intent(this, S4Activity::class.java))
            finish()
            return
        } else if (check == SpikeCheck.S5) {
            startActivity(android.content.Intent(this, S5Activity::class.java))
            finish()
            return
        } else if (check == SpikeCheck.S7) {
            startActivity(android.content.Intent(this, S7Activity::class.java))
            finish()
            return
        } else if (check == SpikeCheck.S6) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            S6Panel.build(this, root, body)
        } else if (check == SpikeCheck.S8) {
            S8Panel.build(this, root, body)
        } else {
            body.text = "${check.id} is not implemented yet (spec task ${check.task})."
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun render(rows: List<Pair<String, String>>) = rows.joinToString("\n\n") { "${it.first}\n${it.second}" }

    companion object {
        const val EXTRA_CHECK = "check"
    }
}
