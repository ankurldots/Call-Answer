package com.callanswer.app.service

import com.callanswer.app.data.currentAgentName
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.callanswer.app.R

/** Notification channel ids. */
object NotificationChannels {
    const val LIVE_CALL = "live_call"
    const val TAKEOVER_ALERT = "takeover_alert"
    const val POST_CALL = "post_call"
    const val ALERTS = "alerts"
}

/** Alert kinds posted on the `alerts` channel. Each has a stable notification id. */
enum class AlertKind(val notificationId: Int) {
    CONTACTS_ACCESS_REQUIRED(2001),
    MAP_LOCATION_MISSING(2002),
    MAP_SMS_FAILED(2003),
    TRANSFER_STARTED(2004),
    TRANSFER_FAILED(2005),
    TRANSFER_TARGET_NOT_CONFIGURED(2006),
    SPEAKERPHONE_OFF(2007),
    SPEAKERPHONE_HEADSET(2030),
    UNKNOWN_CALL_WITH_TRANSCRIPT(2008),
    SILENT_CALLER(2009),
    CALL_NOT_LOGGED(2010),
    RECORDING_NOT_FOUND(2011),
    FOLDER_ACCESS_LOST(2012),
    PERMISSION_REQUIRED(2013),
    CAPTURE_SILENCED(2014),
    ANSWER_FAILED(2020),
    END_CALL_FAILED(2021),
}

/** Creates the notification channels and builds/posts every non-call-state notification. */
class NotificationCenter(private val context: Context) {

    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        val alertAudio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val takeover = NotificationChannel(
            NotificationChannels.TAKEOVER_ALERT,
            context.getString(R.string.channel_takeover_alert),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), alertAudio)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val live = NotificationChannel(
            NotificationChannels.LIVE_CALL,
            context.getString(R.string.channel_live_call),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
        val postCall = NotificationChannel(
            NotificationChannels.POST_CALL,
            context.getString(R.string.channel_post_call),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        val alerts = NotificationChannel(
            NotificationChannels.ALERTS,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        manager.createNotificationChannels(listOf(live, takeover, postCall, alerts))
    }

    /** Intent that opens the app's launcher screen; used as the tap target for alerts. */
    private fun openAppIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, LiveCallActivity::class.java)
        return PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Full-screen / tap target for the Live_Call_Screen. */
    fun liveCallPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, LiveCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alertBuilder(text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, NotificationChannels.ALERTS)
            .setSmallIcon(com.callanswer.app.R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.notif_title_attention))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

    private fun alertText(kind: AlertKind, number: String?, extra: String?): String {
        val n = number ?: ""
        return when (kind) {
            AlertKind.CONTACTS_ACCESS_REQUIRED -> context.getString(R.string.notif_contacts_access_required)
            AlertKind.MAP_LOCATION_MISSING -> context.getString(R.string.notif_map_location_missing, n)
            AlertKind.MAP_SMS_FAILED -> context.getString(R.string.notif_map_sms_failed, n)
            AlertKind.TRANSFER_STARTED -> context.getString(R.string.notif_transfer_started, n)
            AlertKind.TRANSFER_FAILED -> context.getString(R.string.notif_transfer_failed, n)
            AlertKind.TRANSFER_TARGET_NOT_CONFIGURED -> context.getString(R.string.notif_transfer_target_missing, n)
            AlertKind.SPEAKERPHONE_OFF -> context.getString(R.string.notif_speakerphone_off)
            AlertKind.SPEAKERPHONE_HEADSET -> context.getString(R.string.notif_speakerphone_headset)
            AlertKind.UNKNOWN_CALL_WITH_TRANSCRIPT -> context.getString(R.string.notif_unknown_call_transcript, n, extra ?: "")
            AlertKind.SILENT_CALLER -> context.getString(R.string.notif_silent_caller, n)
            AlertKind.CALL_NOT_LOGGED -> context.getString(R.string.notif_call_not_logged, n)
            AlertKind.RECORDING_NOT_FOUND -> context.getString(R.string.notif_recording_not_found, n)
            AlertKind.FOLDER_ACCESS_LOST -> context.getString(R.string.notif_folder_access_lost)
            AlertKind.PERMISSION_REQUIRED -> context.getString(R.string.notif_permission_required, extra ?: "")
            AlertKind.CAPTURE_SILENCED -> context.getString(R.string.notif_capture_silenced, currentAgentName(context))
            AlertKind.ANSWER_FAILED -> context.getString(R.string.notif_answer_failed, n)
            AlertKind.END_CALL_FAILED -> context.getString(R.string.notif_end_call_failed)
        }
    }

    /**
     * Builds the alert for [kind]. [callerNumber] is the Caller's number where the text needs it;
     * [extra] is the transcript (unknown call) or the permission name (permission banners).
     */
    fun buildAlert(kind: AlertKind, callerNumber: String? = null, extra: String? = null): Notification =
        alertBuilder(alertText(kind, callerNumber, extra)).build()

    fun postAlert(kind: AlertKind, callerNumber: String? = null, extra: String? = null) {
        manager.notify(kind.notificationId, buildAlert(kind, callerNumber, extra))
    }

    fun cancelAlert(kind: AlertKind) = manager.cancel(kind.notificationId)

    fun contactsAccessRequired() = postAlert(AlertKind.CONTACTS_ACCESS_REQUIRED)
    fun mapLocationMissing(number: String) = postAlert(AlertKind.MAP_LOCATION_MISSING, number)
    fun mapSmsFailed(number: String) = postAlert(AlertKind.MAP_SMS_FAILED, number)
    fun transferStarted(number: String) = postAlert(AlertKind.TRANSFER_STARTED, number)
    fun transferFailed(number: String) = postAlert(AlertKind.TRANSFER_FAILED, number)
    fun transferTargetNotConfigured(number: String) = postAlert(AlertKind.TRANSFER_TARGET_NOT_CONFIGURED, number)
    fun speakerphoneNotOn() = postAlert(AlertKind.SPEAKERPHONE_OFF)
    fun speakerphoneHeadset() = postAlert(AlertKind.SPEAKERPHONE_HEADSET)
    fun unknownCallWithTranscript(number: String, transcript: String) =
        postAlert(AlertKind.UNKNOWN_CALL_WITH_TRANSCRIPT, number, transcript)
    /**
     * Post-call notification of a screened SOLICITATION call (14.2) on the `post_call` channel. The "Report spam"
     * action is an activity PendingIntent (no trampoline); it only opens the report flow and sends nothing (14.3).
     */
    fun postCallSpam(number: String, entryId: String) {
        val label = number.ifBlank { context.getString(R.string.notif_number_unavailable) }
        val text = context.getString(R.string.notif_spam_call_screened, label)
        val report = PendingIntent.getActivity(
            context,
            entryId.hashCode(),
            Intent().setClassName(context, REPORT_SPAM_ACTIVITY).putExtra(EXTRA_ENTRY_ID, entryId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, NotificationChannels.POST_CALL)
            .setSmallIcon(com.callanswer.app.R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.notif_title_spam_screened))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .addAction(0, context.getString(R.string.notif_action_report_spam), report)
            .build()
        manager.notify(SPAM_NOTIFICATION_BASE + (entryId.hashCode() and 0xFFFF), n)
    }

    /** "Sent to 1909: <text>" after a silent spam report (14.16). Shows only the report text; it is never logged. */
    fun spamReportSent(entryId: String, text: String) = postSpamReportNote(
        entryId, context.getString(R.string.notif_spam_report_sent, text),
    )

    /** Tells the User a silent spam report failed so they can retry from the Call_Log (14.18, 14.24). */
    fun spamReportFailed(entryId: String) = postSpamReportNote(
        entryId, context.getString(R.string.notif_spam_report_failed),
    )

    private fun postSpamReportNote(entryId: String, text: String) {
        val n = NotificationCompat.Builder(context, NotificationChannels.POST_CALL)
            .setSmallIcon(com.callanswer.app.R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.notif_title_spam_report_sent))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        manager.notify(SPAM_REPORT_NOTIFICATION_BASE + (entryId.hashCode() and 0xFFFF), n)
    }

    fun silentCaller(number: String) = postAlert(AlertKind.SILENT_CALLER, number)
    fun callNotLogged(number: String) = postAlert(AlertKind.CALL_NOT_LOGGED, number)
    fun recordingNotFound(number: String) = postAlert(AlertKind.RECORDING_NOT_FOUND, number)
    fun folderAccessLost() = postAlert(AlertKind.FOLDER_ACCESS_LOST)
    fun captureSilenced() = postAlert(AlertKind.CAPTURE_SILENCED)
    fun permissionBanner(permissionName: String) = postAlert(AlertKind.PERMISSION_REQUIRED, extra = permissionName)

    companion object {
        /** Activity that starts the report flow (task 31.3). Referenced by name so `:app` compiles without it. */
        const val REPORT_SPAM_ACTIVITY = "com.callanswer.app.report.ReportSpamActivity"
        const val EXTRA_ENTRY_ID = "entryId"
        private const val SPAM_NOTIFICATION_BASE = 3000
        private const val SPAM_REPORT_NOTIFICATION_BASE = 80_000
    }
}
