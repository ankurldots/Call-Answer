package com.callanswer.app.sms

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.callanswer.core.report.TraiReport

/** Opens the messaging app with the report prefilled. The destination is fixed to [TraiReport.NUMBER] (14.28). */
interface SmsIntentLauncher {
    /** Returns false when no messaging app can handle the intent (14.19). */
    fun openComposer(body: String): Boolean
}

class AndroidSmsIntentLauncher(private val context: Context) : SmsIntentLauncher {
    override fun openComposer(body: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("sms:${TraiReport.NUMBER}"))
            .putExtra("sms_body", body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
