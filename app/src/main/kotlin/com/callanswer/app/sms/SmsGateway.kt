package com.callanswer.app.sms

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.callanswer.app.BuildConfig
import com.callanswer.core.report.TraiReport

/** Silent send to the TRAI number. No destination parameter exists, so nothing else can be reached (14.28). */
interface SmsGateway {
    /** Splits [text] into SMS parts with `SmsManager.divideMessage`. */
    fun divide(text: String): List<String>

    /** Sends [parts] to [TraiReport.NUMBER]; [sent] holds one PendingIntent per part. No-op when the silent path is compiled out. */
    fun sendToTrai(parts: List<String>, sent: List<PendingIntent>)
}

class AndroidSmsGateway(
    private val context: Context,
    private val silentSmsEnabled: Boolean = BuildConfig.SILENT_SMS_ENABLED,
) : SmsGateway {

    override fun divide(text: String): List<String> = smsManager().divideMessage(text)

    override fun sendToTrai(parts: List<String>, sent: List<PendingIntent>) {
        if (!silentSmsEnabled || parts.isEmpty()) return
        require(sent.size == parts.size) { "one sent PendingIntent per part" }
        val sms = smsManager()
        if (parts.size == 1) {
            sms.sendTextMessage(TraiReport.NUMBER, null, parts[0], sent[0], null)
        } else {
            sms.sendMultipartTextMessage(TraiReport.NUMBER, null, ArrayList(parts), ArrayList(sent), null)
        }
    }

    private fun smsManager(): SmsManager {
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val valid = subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            return if (valid) base.createForSubscriptionId(subId) else base
        }
        @Suppress("DEPRECATION") // SmsManager.getDefault/getSmsManagerForSubscriptionId are the only options below API 31
        return if (valid) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
    }
}
