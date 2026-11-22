package com.callanswer.app.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import android.telephony.TelephonyManager
import com.callanswer.app.service.AlertKind
import com.callanswer.app.service.NotificationCenter
import com.callanswer.core.phone.CallAnswerer
import kotlinx.coroutines.delay

/**
 * Answers the ringing call with [TelecomManager.acceptRingingCall]. Every [RETRY_INTERVAL_MS] it reads the
 * phone state: OFFHOOK means the call was answered; RINGING means the accept is issued (again); any other
 * state means the ring has not started yet. After [RETRY_LIMIT_MS] without OFFHOOK it posts the
 * "could not answer" notification and returns false.
 */
class TelecomCallAnswerer(
    context: Context,
    private val callerNumber: String? = null,
    /** True when the ring is known to be under way (fallback tap): an idle phone then means the caller gave up. */
    private val requireRinging: Boolean = false,
) : CallAnswerer {
    private val app = context.applicationContext

    override suspend fun answer(): Boolean {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return failed()
        val tm = app.getSystemService(TelecomManager::class.java) ?: return failed()
        val start = SystemClock.elapsedRealtime()
        Log.i(TAG, "answer requested")
        while (true) {
            val state = callState()
            if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                Log.i(TAG, "answered (OFFHOOK)")
                return true
            }
            if (requireRinging && state == TelephonyManager.CALL_STATE_IDLE) {
                Log.i(TAG, "not answering: call is no longer ringing")
                return false
            }
            if (SystemClock.elapsedRealtime() - start > RETRY_LIMIT_MS) return failed()
            if (state == TelephonyManager.CALL_STATE_RINGING) acceptRinging(tm)
            delay(RETRY_INTERVAL_MS)
        }
    }

    /** Ends the active call; posts a notification when the platform refuses or the permission is missing. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun endCall(): Boolean {
        val ok = try {
            granted(Manifest.permission.ANSWER_PHONE_CALLS) &&
                app.getSystemService(TelecomManager::class.java)?.endCall() == true
        } catch (e: Exception) {
            false
        }
        if (!ok) NotificationCenter(app).postAlert(AlertKind.END_CALL_FAILED)
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun acceptRinging(tm: TelecomManager) {
        try {
            tm.acceptRingingCall()
        } catch (_: Exception) {
            // Retried on the next tick.
        }
    }

    private fun failed(): Boolean {
        Log.i(TAG, "answer failed: accept issued but the call never went OFFHOOK, or permission missing")
        NotificationCenter(app).postAlert(AlertKind.ANSWER_FAILED, callerNumber)
        return false
    }

    private fun granted(permission: String) =
        app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun callState(): Int? = try {
        app.getSystemService(TelephonyManager::class.java)?.callState
    } catch (e: SecurityException) {
        null
    }

    companion object {
        private const val TAG = "CallAnswer"
        const val RETRY_INTERVAL_MS = 100L
        const val RETRY_LIMIT_MS = 2_000L
    }
}
