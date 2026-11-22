package com.callanswer.spike

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Spike S8: 1909 hand-off and send.
 * - ACTION_SENDTO sms:1909 with sms_body "Unsolicited sales call, <number>, <DD/MM/YY>"
 * - SmsManager.sendTextMessage / sendMultipartTextMessage to "1909", sent PendingIntent result codes logged
 * - default SMS subscription and active SIMs logged (dual SIM)
 * Run with airplane mode on and with no signal; the airplane mode state is logged with every result.
 */
object S8Panel {
    private const val TAG = "S8"
    private const val ACTION_SENT = "com.callanswer.spike.S8_SENT"
    private const val DEST = "1909"
    private var seq = 0
    private var receiver: BroadcastReceiver? = null

    fun build(activity: Activity, root: LinearLayout, body: TextView) {
        val number = EditText(activity).apply {
            hint = "Spam number, e.g. +919876543210"
            setText("+919876543210")
        }
        root.addView(TextView(activity).apply { text = "Number to report (use a real spam number)" })
        root.addView(number)

        fun report(): String = reportBody(number.text.toString().trim())

        root.addView(btn(activity, "Hand off via ACTION_SENDTO sms:1909") {
            val text = report()
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$DEST")).apply {
                data = Uri.parse("sms:$DEST")
                putExtra("sms_body", text)
            }
            val resolved = activity.packageManager.queryIntentActivities(intent, 0)
                .joinToString { it.activityInfo.packageName }
            SpikeLog.log(TAG, "SENDTO handlers=[$resolved] body=\"$text\" (${text.length} chars)")
            try {
                activity.startActivity(intent)
                SpikeLog.log(TAG, "SENDTO started; confirm recipient 1909 and prefilled body in the messaging app")
            } catch (e: Exception) {
                SpikeLog.log(TAG, "SENDTO failed: $e")
            }
            body.text = "SENDTO: handlers=[$resolved]"
        })

        root.addView(btn(activity, "SmsManager.sendTextMessage to 1909") {
            send(activity, report(), multipart = false)
            body.text = "Sent request logged. See log for result code."
        })

        root.addView(btn(activity, "SmsManager.sendMultipartTextMessage (long body)") {
            val long = report() + " " + "x".repeat(200)
            send(activity, long, multipart = true)
            body.text = "Multipart request logged. See log for result codes."
        })

        root.addView(btn(activity, "Log SIM / default SMS subscription") {
            logSubscriptions(activity)
            body.text = "SIM info written to log."
        })

        root.addView(btn(activity, "Log airplane mode / state") {
            SpikeLog.log(TAG, "state: ${environment(activity)}")
            body.text = environment(activity)
        })

        body.text = "Report format: ${report()}\n" +
            "Run each send with airplane mode on and with no signal, and once with signal."
        registerReceiver(activity)
    }

    fun dispose(activity: Activity) {
        receiver?.let { runCatching { activity.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun reportBody(number: String): String {
        val date = SimpleDateFormat("dd/MM/yy", Locale.US).format(Date())
        return "Unsolicited sales call, $number, $date"
    }

    private fun registerReceiver(activity: Activity) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val part = intent.getIntExtra("part", 0)
                val of = intent.getIntExtra("parts", 1)
                val id = intent.getIntExtra("id", -1)
                val code = resultCode
                SpikeLog.log(
                    TAG,
                    "sent result id=$id part=${part + 1}/$of code=$code (${describe(code)}) " +
                        "errorCode=${intent.getIntExtra("errorCode", -1)} ${environment(context)}"
                )
            }
        }
        val filter = IntentFilter(ACTION_SENT)
        val ctx = activity.applicationContext
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, filter)
        receiver = r
    }

    private fun send(activity: Activity, text: String, multipart: Boolean) {
        if (activity.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            SpikeLog.log(TAG, "SEND_SMS not granted; use 'Request runtime permissions' on the menu")
            Toast.makeText(activity, "SEND_SMS not granted", Toast.LENGTH_LONG).show()
            return
        }
        val id = ++seq
        val sms = smsManager(activity)
        SpikeLog.log(
            TAG,
            "send id=$id multipart=$multipart dest=$DEST chars=${text.length} defaultSmsSubId=${defaultSubId()} ${environment(activity)}"
        )
        try {
            if (!multipart) {
                sms.sendTextMessage(DEST, null, text, sentIntent(activity, id, 0, 1), null)
            } else {
                val parts = sms.divideMessage(text)
                SpikeLog.log(TAG, "multipart id=$id parts=${parts.size}")
                val sent = ArrayList<PendingIntent>()
                parts.indices.forEach { sent.add(sentIntent(activity, id * 100 + it, it, parts.size, id)) }
                sms.sendMultipartTextMessage(DEST, null, parts, sent, null)
            }
        } catch (e: Exception) {
            SpikeLog.log(TAG, "send id=$id threw $e")
        }
    }

    private fun sentIntent(ctx: Context, reqCode: Int, part: Int, parts: Int, id: Int = reqCode): PendingIntent {
        val i = Intent(ACTION_SENT).setPackage(ctx.packageName)
            .putExtra("id", id).putExtra("part", part).putExtra("parts", parts)
        return PendingIntent.getBroadcast(
            ctx, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    @Suppress("DEPRECATION")
    private fun smsManager(ctx: Context): SmsManager {
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        return sms
    }

    private fun defaultSubId(): Int = SubscriptionManager.getDefaultSmsSubscriptionId()

    private fun logSubscriptions(ctx: Context) {
        SpikeLog.log(TAG, "default SMS subscriptionId=${defaultSubId()} defaultVoice=${SubscriptionManager.getDefaultVoiceSubscriptionId()}")
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            SpikeLog.log(TAG, "READ_PHONE_STATE not granted; cannot list SIMs")
            return
        }
        val sm = ctx.getSystemService(SubscriptionManager::class.java)
        val list = try { sm.activeSubscriptionInfoList.orEmpty() } catch (e: SecurityException) { emptyList() }
        SpikeLog.log(TAG, "active SIMs=${list.size} dualSim=${list.size > 1}")
        list.forEach {
            SpikeLog.log(
                TAG,
                "SIM slot=${it.simSlotIndex} subId=${it.subscriptionId} carrier=${it.carrierName} " +
                    "isDefaultSms=${it.subscriptionId == defaultSubId()}"
            )
        }
    }

    private fun environment(ctx: Context): String {
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        return "airplaneMode=$airplane"
    }

    private fun describe(code: Int): String = when (code) {
        Activity.RESULT_OK -> "RESULT_OK"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
        SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "LIMIT_EXCEEDED"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "SHORT_CODE_NOT_ALLOWED"
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "SHORT_CODE_NEVER_ALLOWED"
        else -> "OTHER"
    }

    private fun btn(ctx: Context, label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
