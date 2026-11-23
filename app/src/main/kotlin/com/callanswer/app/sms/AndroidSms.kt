package com.callanswer.app.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.edit
import com.callanswer.app.CallAnswerApp
import java.util.concurrent.atomic.AtomicInteger

/** `SmsManager` for one subscription; every text goes out with `divideMessage` and `sendMultipartTextMessage`. */
class SmsManagerTransport(private val context: Context) : SmsTransport {
    private val requestCodes = AtomicInteger(0x5000)

    override fun send(subscriptionId: Int, number: String, text: String, attemptId: String): Int {
        val app = context.applicationContext
        val sms = smsManager(app, subscriptionId)
        val parts = ArrayList(sms.divideMessage(text))
        val sent = ArrayList<PendingIntent>()
        val delivered = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            sent += resultIntent(app, attemptId, MapSmsResultReceiver.KIND_SENT)
            delivered += resultIntent(app, attemptId, MapSmsResultReceiver.KIND_DELIVERED)
        }
        sms.sendMultipartTextMessage(number, null, parts, sent, delivered)
        return parts.size
    }

    @Suppress("DEPRECATION")
    private fun smsManager(app: Context, subscriptionId: Int): SmsManager {
        val base = app.getSystemService(SmsManager::class.java) ?: throw IllegalStateException("no SmsManager")
        if (subscriptionId < 0) return base
        return if (Build.VERSION.SDK_INT >= 31) base.createForSubscriptionId(subscriptionId)
        else SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    }

    private fun resultIntent(app: Context, attemptId: String, kind: String): PendingIntent {
        val intent = Intent(app, MapSmsResultReceiver::class.java)
            .putExtra(MapSmsResultReceiver.EXTRA_ATTEMPT, attemptId)
            .putExtra(MapSmsResultReceiver.EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(
            app, requestCodes.incrementAndGet(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
    }
}

/** Receives the sent and delivered results of the parts. Not exported: only the PendingIntents of [SmsManagerTransport] reach it. */
class MapSmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? CallAnswerApp ?: return
        val ok = resultCode == Activity.RESULT_OK
        when (intent.getStringExtra(EXTRA_KIND)) {
            KIND_SENT -> app.mapSmsSender.onPartSent(intent.getStringExtra(EXTRA_ATTEMPT).orEmpty(), ok)
            KIND_DELIVERED -> app.mapSmsSender.onPartDelivered(ok)
        }
    }

    companion object {
        const val EXTRA_ATTEMPT = "attemptId"
        const val EXTRA_KIND = "kind"
        const val KIND_SENT = "sent"
        const val KIND_DELIVERED = "delivered"
    }
}

/** Reads the SIM subscriptions. Only the list of active subscriptions needs `READ_PHONE_STATE`. */
class AndroidSubscriptionSource(private val context: Context) : SubscriptionSource {
    override fun defaultSmsSubscriptionId(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()

    override fun activeSubscriptionIds(): List<Int>? = activeSubscriptions(context)?.map { it.id }
}

/** One active SIM for the picker. */
data class SimOption(val id: Int, val label: String)

/** True when the phone can hold more than one SIM at once. Needs no permission. */
fun isMultiSimDevice(context: Context): Boolean {
    val tm = context.getSystemService(TelephonyManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= 30) tm.activeModemCount > 1 else @Suppress("DEPRECATION") (tm.phoneCount > 1)
}

/** Active subscriptions, or null without `READ_PHONE_STATE`. */
@android.annotation.SuppressLint("MissingPermission") // checked on the first line
fun activeSubscriptions(context: Context): List<SimOption>? {
    if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
    val sm = context.getSystemService(SubscriptionManager::class.java) ?: return null
    return try {
        sm.activeSubscriptionInfoList.orEmpty().map { info ->
            val name = info.displayName?.toString().orEmpty().ifBlank { "SIM ${info.simSlotIndex + 1}" }
            SimOption(info.subscriptionId, "SIM ${info.simSlotIndex + 1}: $name")
        }
    } catch (_: SecurityException) {
        null
    }
}

/**
 * Settings that stay on this device and are never sent to the server (Requirement 10.15): the SIM the User picked, and
 * the ids of the calls whose Map_SMS request was handled (the last [MAX_HANDLED]), so a repeated push sends nothing.
 */
class LocalSmsSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sms_local", Context.MODE_PRIVATE)

    var selectedSubscriptionId: Int?
        get() = if (prefs.contains(KEY_SIM)) prefs.getInt(KEY_SIM, -1) else null
        set(value) = prefs.edit { if (value == null) remove(KEY_SIM) else putInt(KEY_SIM, value) }

    /** True the first time [callId] is seen. */
    @Synchronized
    fun claim(callId: String): Boolean {
        val ids = prefs.getString(KEY_HANDLED, "").orEmpty().split('\n').filter { it.isNotEmpty() }
        if (callId in ids) return false
        val next = (ids + callId).takeLast(MAX_HANDLED)
        // commit(): the id must be stored before the text is sent, or a restart could send it twice.
        prefs.edit().putString(KEY_HANDLED, next.joinToString("\n")).commit()
        return true
    }

    private companion object {
        const val KEY_SIM = "sim_subscription_id"
        const val KEY_HANDLED = "handled_call_ids"
        const val MAX_HANDLED = 200
    }
}
