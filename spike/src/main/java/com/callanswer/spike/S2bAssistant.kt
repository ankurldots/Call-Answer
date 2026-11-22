package com.callanswer.spike

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Helpers for S2b: role state and role/settings intents. */
object S2bAssistant {
    fun roleHeld(ctx: Context): Boolean = try {
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } catch (_: Throwable) { false }

    fun roleAvailable(ctx: Context): Boolean = try {
        ctx.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    } catch (_: Throwable) { false }

    fun screeningRoleHeld(ctx: Context): Boolean = try {
        ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    } catch (_: Throwable) { false }

    /** Intent to ask for the assistant role; null if the role is unavailable or already held. */
    fun requestIntent(ctx: Context): Intent? {
        val rm = ctx.getSystemService(RoleManager::class.java)
        return if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT))
            rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) else null
    }

    /** Settings screens for choosing the default assistant, most specific first. */
    fun settingsIntents(): List<Intent> = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    )

    fun openSettings(ctx: Context) {
        val ok = settingsIntents().any { try { ctx.startActivity(it); true } catch (_: Throwable) { false } }
        if (!ok) Toast.makeText(ctx, "No settings screen found", Toast.LENGTH_LONG).show()
    }
}

/**
 * S2b: minimal VoiceInteractionService so the spike is eligible for ROLE_ASSISTANT.
 * Runs the S2 capture loop in this service's process context when asked (the system binds it only while it is the assistant).
 */
class SpikeAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        SpikeLog.init(this)
        instance = this
        SpikeLog.log("S2b", "VoiceInteractionService onReady (spike is the active voice interaction service)")
    }

    override fun onShutdown() {
        SpikeLog.log("S2b", "VoiceInteractionService onShutdown")
        if (instance === this) instance = null
        super.onShutdown()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun runCapture(seconds: Int, only: String?, waitOffhook: Boolean, delaySec: Int) {
        Thread {
            if (waitOffhook) S2Capture.waitForOffhook(this, 120) else Thread.sleep(delaySec * 1000L)
            S2Capture.runAll(this, seconds, only, "VIS")
        }.start()
    }

    companion object {
        @Volatile var instance: SpikeAssistantService? = null
    }
}

class SpikeAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SpikeAssistantSession(this)
}

/** Minimal session: shows nothing. */
class SpikeAssistantSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        SpikeLog.log("S2b", "assistant session shown (flags=$showFlags)")
        hide()
    }
}

/** Named in the voice-interaction metadata. Does no recognition. */
class SpikeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } catch (_: Throwable) {}
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}

/** Target of ACTION_ASSIST (some role checks want an assist activity). Closes at once. */
class SpikeAssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        SpikeLog.log("S2b", "ACTION_ASSIST activity launched")
        finish()
    }
}

/** S2b screen: role request, role state, and the capture runs (VIS context and plain foreground service). */
class S2bActivity : Activity() {
    private lateinit var state: TextView

    override fun onResume() {
        super.onResume()
        state.text = "S1 mode: ${S1Settings.describe(this)}\n" +
            "assistant role available: ${S2bAssistant.roleAvailable(this)}\n" +
            "assistant role held by spike: ${S2bAssistant.roleHeld(this)}\n" +
            "VoiceInteractionService running (onReady seen): ${SpikeAssistantService.instance != null}\n" +
            "call screening role held by spike: ${S2bAssistant.screeningRoleHeld(this)}"
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        SpikeLog.log("S2b", "assistant role request result=${if (resultCode == RESULT_OK) "granted" else "denied/cancelled"} held=${S2bAssistant.roleHeld(this)}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)
        title = "S2b Assistant-role capture"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        state = TextView(this).apply { textSize = 14f }
        root.addView(state)
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "Hold the assistant role, set S1 mode to AUTO_ANSWER_ONLY, call from the second phone on loudspeaker and speak. " +
                "Sources: VOICE_RECOGNITION, MIC, VOICE_COMMUNICATION, UNPROCESSED. Pass: any source with rms clearly above 0 and isClientSilenced=false. " +
                "Only one app can hold the call screening role: if the Call Answer app holds it, switch it to the spike first."
        })
        root.addView(btn("Request assistant role") {
            val i = S2bAssistant.requestIntent(this)
            if (i == null) Toast.makeText(this, "Role unavailable or already held", Toast.LENGTH_LONG).show()
            else try { startActivityForResult(i, 3) } catch (e: Throwable) { SpikeLog.log("S2b", "role request failed: $e") }
        })
        root.addView(btn("Open default assistant settings") { S2bAssistant.openSettings(this) })
        val offhook = CheckBox(this).apply { text = "Start capture when the call becomes OFFHOOK (waits up to 120 s)"; isChecked = true }
        val delay = EditText(this).apply { hint = "Start delay, seconds (if not OFFHOOK)"; setText("10"); inputType = 2 }
        val secs = EditText(this).apply { hint = "Seconds per source"; setText("10"); inputType = 2 }
        root.addView(offhook); root.addView(delay); root.addView(secs)
        root.addView(btn("Start capture in VoiceInteractionService (VIS)") {
            if (!hasMic()) return@btn
            val s = secs.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 10
            val d = delay.text.toString().toIntOrNull() ?: 0
            val svc = SpikeAssistantService.instance
            if (svc == null) {
                SpikeLog.log("S2b", "VIS capture not started: service not running (assistant role not held, or spike not selected as assistant)")
                Toast.makeText(this, "VoiceInteractionService not running. Hold the assistant role first.", Toast.LENGTH_LONG).show()
            } else {
                SpikeLog.log("S2b", "VIS capture requested, roleHeld=${S2bAssistant.roleHeld(this)}")
                svc.runCapture(s, null, offhook.isChecked, d)
                Toast.makeText(this, "Started in VIS. Place the call now.", Toast.LENGTH_LONG).show()
            }
        })
        root.addView(btn("Start capture in S2CaptureService (comparison)") {
            if (!hasMic()) return@btn
            val s = secs.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 10
            val d = delay.text.toString().toIntOrNull() ?: 0
            SpikeLog.log("S2b", "FGS capture requested, roleHeld=${S2bAssistant.roleHeld(this)}")
            startForegroundService(
                Intent(this, S2CaptureService::class.java)
                    .putExtra(S2CaptureService.EXTRA_SECONDS, s)
                    .putExtra(S2CaptureService.EXTRA_DELAY, d)
                    .putExtra(S2CaptureService.EXTRA_WAIT_OFFHOOK, offhook.isChecked)
            )
            Toast.makeText(this, "Started in foreground service. Place the call now.", Toast.LENGTH_LONG).show()
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun hasMic(): Boolean {
        val ok = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!ok) Toast.makeText(this, "Grant RECORD_AUDIO on the main screen", Toast.LENGTH_LONG).show()
        return ok
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
    }
}
