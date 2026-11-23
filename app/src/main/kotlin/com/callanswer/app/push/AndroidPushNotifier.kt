package com.callanswer.app.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import com.callanswer.app.R
import com.callanswer.app.ui.screens.SetupStatus

/**
 * Takeover_Alert (high importance, sound, full-screen intent when allowed, "Take over" action) and the live-call
 * notification. The content tap opens the live call screen; "Take over" opens it and calls `POST /calls/{id}/takeover`.
 */
class AndroidPushNotifier(private val context: Context) : PushNotifier {
    override fun showTakeoverAlert(callId: String, number: String?) = post(takeoverId(callId)) { manager ->
        ensureTakeoverChannel(manager)
        val open = activity(LiveCallRequest(callId, takeOver = false), 0)
        val takeOver = activity(LiveCallRequest(callId, takeOver = true), 1)
        val shown = number ?: context.getString(R.string.takeover_alert_unknown_number)
        val builder = Notification.Builder(context, TAKEOVER_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.takeover_alert_title))
            .setContentText(context.getString(R.string.takeover_alert_text, shown))
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.call_action_take_over), takeOver).build())
        if (SetupStatus.canUseFullScreenIntent(context)) builder.setFullScreenIntent(open, true)
        builder.build()
    }

    override fun showCallStarted(callId: String, number: String?) = post(liveId(callId)) { manager ->
        ensureLiveChannel(manager)
        Notification.Builder(context, LIVE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.channel_live_call))
            .setContentText(number ?: context.getString(R.string.takeover_alert_unknown_number))
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setContentIntent(activity(LiveCallRequest(callId, takeOver = false), 2))
            .build()
    }

    override fun clearCall(callId: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(liveId(callId))
        manager.cancel(takeoverId(callId))
    }

    private fun activity(request: LiveCallRequest, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code, request.toIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun post(id: Int, build: (NotificationManager) -> Notification) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            manager.notify(id, build(manager))
        } catch (e: RuntimeException) {
            Log.w("CallAnswer", "push notification failed: ${e.javaClass.simpleName}")
        }
    }

    private fun ensureTakeoverChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(TAKEOVER_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            TAKEOVER_CHANNEL_ID, context.getString(R.string.channel_takeover_alert), NotificationManager.IMPORTANCE_HIGH,
        )
        channel.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
    }

    private fun ensureLiveChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(LIVE_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(LIVE_CHANNEL_ID, context.getString(R.string.channel_live_call), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val TAKEOVER_CHANNEL_ID = "TAKEOVER_ALERT"
        const val LIVE_CHANNEL_ID = "LIVE_CALL"
        fun takeoverId(callId: String) = 0x4000_0000 or (callId.hashCode() and 0x3FFF_FFFF)
        fun liveId(callId: String) = 0x2000_0000 or (callId.hashCode() and 0x1FFF_FFFF)
    }
}
