package com.callanswer.app.screening

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.callanswer.app.MainActivity
import com.callanswer.app.R
import com.callanswer.app.ui.screens.SetupStatus
import com.callanswer.core.model.IntentCategory

/**
 * The Ringing_Alert (Requirement 33): a high-priority heads-up notification, with a full-screen intent when the
 * User has granted that permission, for a Repeat_Caller while the phone rings. The system removes it after
 * [TIMEOUT_MS]; [cancel] removes it earlier. The screening role gives no call-end signal and the app holds no
 * phone-state permission, so the 25 seconds are the bound for a call that ends sooner.
 */
class RingingAlert(private val context: Context) : RepeatCallerAlerter {

    override fun onRepeatCaller(match: RepeatMatch) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannel(manager)
            manager.notify(NOTIFICATION_ID, build(match))
        } catch (e: RuntimeException) {
            Log.w("CallAnswer", "ringing alert failed: ${e.javaClass.simpleName}")
        }
    }

    /** Removes the alert, for example when the call ends. */
    fun cancel() {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun build(match: RepeatMatch): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.ringing_alert_title, match.e164))
            .setContentText(text(context, match))
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setTimeoutAfter(TIMEOUT_MS)
        if (SetupStatus.canUseFullScreenIntent(context)) builder.setFullScreenIntent(open, true)
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "REPEAT_CALLER_ALERT"
        const val NOTIFICATION_ID = 3301
        const val TIMEOUT_MS = 25_000L

        /** Text of the form "Delivery caller from 5 min ago - pick up". */
        fun text(context: Context, match: RepeatMatch): String =
            context.getString(R.string.ringing_alert_text, intentLabel(context, match.record.intent), match.minutesAgo)

        private fun intentLabel(context: Context, intent: IntentCategory): String = context.getString(
            when (intent) {
                IntentCategory.DELIVERY -> R.string.ringing_alert_intent_delivery
                IntentCategory.DOOR_NOT_OPENED -> R.string.ringing_alert_intent_door
                else -> R.string.ringing_alert_intent_unknown
            },
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_repeat_caller_alert),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }
}
