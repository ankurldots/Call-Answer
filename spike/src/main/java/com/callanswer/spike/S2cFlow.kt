package com.callanswer.spike

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * S2c: accessibility only during the call. Armed from the S2c screen; on OFFHOOK it enables our accessibility
 * service, waits for onServiceConnected, turns the speakerphone on and captures inside the service; on IDLE it
 * disables the service again.
 */
object S2cFlow {
    private const val TAG = "S2c"
    @Volatile var armed = false
        private set
    @Volatile private var running = false
    @Volatile private var sawOffhook = false
    private var connectedLatch = CountDownLatch(1)
    private var callback: TelephonyCallback? = null
    private var only: String? = "VOICE_RECOGNITION"
    private var seconds = 10
    private lateinit var app: Context

    /** Source names for the selector: ordered as the spec asks (VOICE_RECOGNITION first), plus ALL. */
    val SOURCE_CHOICES = listOf("ALL sources") + S2Capture.SOURCES.map { it.first }

    fun arm(ctx: Context, source: String?, secondsPerSource: Int): String? {
        app = ctx.applicationContext
        SpikeLog.init(app)
        if (!A11yToggle.hasPermission(app)) return "WRITE_SECURE_SETTINGS not granted. Run the adb grant command first."
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "Grant RECORD_AUDIO on the main screen."
        if (app.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "Grant READ_PHONE_STATE on the main screen."
        if (A11yToggle.callIsActive(app)) return "A call is already active. Arm before the call."
        disarm("re-arm")
        only = source
        seconds = secondsPerSource
        sawOffhook = false
        running = false
        connectedLatch = CountDownLatch(1)
        val tm = app.getSystemService(TelephonyManager::class.java)
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onState(state)
        }
        tm.registerTelephonyCallback(app.mainExecutor, cb)
        callback = cb
        armed = true
        SpikeLog.log(TAG, "armed source=${source ?: "ALL"} secondsPerSource=$secondsPerSource s1Mode=${S1Settings.describe(app)} " +
            "screeningRoleHeld=${S2bAssistant.screeningRoleHeld(app)}")
        return null
    }

    fun disarm(reason: String) {
        if (!armed && callback == null) return
        try { callback?.let { app.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it) } } catch (_: Throwable) {}
        callback = null
        armed = false
        SpikeLog.log(TAG, "disarmed ($reason)")
    }

    private fun onState(state: Int) {
        SpikeLog.log(TAG, "call state=${when (state) { 0 -> "IDLE"; 1 -> "RINGING"; 2 -> "OFFHOOK"; else -> state }}")
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> if (!running && !sawOffhook) {
                sawOffhook = true
                running = true
                Thread { runInCall() }.start()
            }
            TelephonyManager.CALL_STATE_IDLE -> if (sawOffhook) {
                A11yToggle.disable(app, "call IDLE")
                SpikeLog.log(TAG, "accessibility disabled after call (still enabled=${A11yToggle.isEnabled(app)})")
                disarm("call ended")
            }
        }
    }

    fun onServiceConnected(@Suppress("UNUSED_PARAMETER") service: SpikeCallAudioA11yService) { connectedLatch.countDown() }

    private fun runInCall() {
        try {
            SpikeLog.log(TAG, "OFFHOOK: enabling accessibility")
            if (!A11yToggle.enable(app, "call OFFHOOK")) return
            if (!connectedLatch.await(8, TimeUnit.SECONDS)) {
                SpikeLog.log(TAG, "service not connected after 8 s; no capture. Check restricted settings (App info).")
                return
            }
            Thread.sleep(1500) // let the in-call audio path settle
            val svc = SpikeCallAudioA11yService.instance ?: run { SpikeLog.log(TAG, "service instance missing"); return }
            val am = svc.getSystemService(AudioManager::class.java)
            val before = am.isSpeakerphoneOn
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
            SpikeLog.log(TAG, "speakerphone requested on (was $before) now=${am.isSpeakerphoneOn}")
            S2Capture.runAll(svc, seconds, only, "A11Y")
            @Suppress("DEPRECATION")
            if (!before) am.isSpeakerphoneOn = false
            SpikeLog.log(TAG, "capture finished; waiting for the call to end to disable accessibility")
        } catch (e: Throwable) {
            SpikeLog.log(TAG, "flow failed: $e")
        } finally {
            running = false
        }
    }
}
