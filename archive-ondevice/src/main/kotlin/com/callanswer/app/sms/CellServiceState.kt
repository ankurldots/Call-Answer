package com.callanswer.app.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.ServiceState
import android.telephony.TelephonyManager

/** Cellular service check before a silent send (14.24). */
interface CellServiceState {
    /** True in service, false when out of service, emergency only or radio off, null when unknown (no READ_PHONE_STATE). */
    fun inService(): Boolean?
}

class AndroidCellServiceState(private val context: Context) : CellServiceState {
    // READ_PHONE_STATE is checked above. Lint also asks for ACCESS_COARSE_LOCATION because ServiceState
    // carries cell identity, but we read only the coarse registration state, which does not need location;
    // location-bearing fields come back redacted. Declaring location would be an unwanted privacy cost.
    @SuppressLint("MissingPermission")
    override fun inService(): Boolean? {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val state = context.getSystemService(TelephonyManager::class.java)?.serviceState ?: return null
            state.state == ServiceState.STATE_IN_SERVICE
        } catch (_: SecurityException) {
            null
        }
    }
}
