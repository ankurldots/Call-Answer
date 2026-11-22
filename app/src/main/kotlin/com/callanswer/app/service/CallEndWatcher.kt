package com.callanswer.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Detects that the call is over: after the phone has been RINGING or OFFHOOK, the state returns to IDLE.
 * This covers the Caller hanging up and a system disconnect; [onCallEnded] fires once. Needs READ_PHONE_STATE;
 * without it the watcher does nothing.
 */
class CallEndWatcher(context: Context, private val onCallEnded: () -> Unit) {
    private val app = context.applicationContext
    private val telephony: TelephonyManager? = app.getSystemService(TelephonyManager::class.java)
    private var seenActive = false
    private var fired = false
    private var callback: Any? = null

    private fun onState(state: Int) {
        if (state != TelephonyManager.CALL_STATE_IDLE) {
            seenActive = true
        } else if (seenActive && !fired) {
            fired = true
            onCallEnded()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start() {
        val tm = telephony ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(state)
                }
                callback = cb
                tm.registerTelephonyCallback(app.mainExecutor, cb)
            } else {
                val l = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) = onState(state)
                }
                callback = l
                tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (_: SecurityException) {
            callback = null
        }
    }

    @Suppress("DEPRECATION")
    fun stop() {
        val tm = telephony ?: return
        val cb = callback ?: return
        callback = null
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) tm.unregisterTelephonyCallback(cb as TelephonyCallback)
            else tm.listen(cb as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
    }
}
