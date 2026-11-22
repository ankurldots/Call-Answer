package com.callanswer.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.callanswer.app.R
import com.callanswer.app.audio.AgentAudioPlayer
import com.callanswer.core.model.AlertReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Path B (Requirements 4.2, 4.5, 4.7-4.11, 13.21). */
interface TakeoverAlertPresenter {
    /** Posts the `takeover_alert` notification and starts the alert tone. */
    fun raise(callerNumber: String?, reason: AlertReason)

    /** Stops the tone (at 30 s); the notification stays until [clear]. */
    fun stopTone()

    /** Cancels the tone and the notification (User took over, or the call ended). */
    fun clear()
}

/**
 * Notification on channel `takeover_alert` with a full-screen intent to the Live_Call_Screen (heads-up only
 * when full-screen intent access is missing) and one Take over action that reaches [CallForegroundService]
 * as `UserTakeOver(origin = ALERT)`. The tone plays through [player] at 5 s, 15 s and 25 s, which is 5 s off
 * the "please wait" phrases at 0 s, 10 s and 20 s, and stops after [TONE_WINDOW_MS].
 */
class TakeoverAlertPresenterImpl(
    private val context: Context,
    private val player: AgentAudioPlayer,
    private val scope: CoroutineScope,
) : TakeoverAlertPresenter {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val lock = Any()
    private var toneJob: Job? = null

    override fun raise(callerNumber: String?, reason: AlertReason) {
        manager.notify(NOTIFICATION_ID, buildNotification(callerNumber, reason))
        synchronized(lock) {
            toneJob?.cancel()
            toneJob = scope.launch {
                var at = TONE_OFFSET_MS
                var elapsed = 0L
                while (at < TONE_WINDOW_MS) {
                    delay(at - elapsed)
                    elapsed = at
                    runCatching { player.play(TONE_PCM, TONE_RATE, "alert-tone") }
                    at += TONE_INTERVAL_MS
                }
                delay(TONE_WINDOW_MS - elapsed)
            }
        }
    }

    override fun stopTone() {
        synchronized(lock) { toneJob?.cancel(); toneJob = null }
    }

    override fun clear() {
        stopTone()
        manager.cancel(NOTIFICATION_ID)
    }

    /** True when the notification may carry a full-screen intent (always before Android 14). */
    fun hasFullScreenIntentAccess(): Boolean =
        Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()

    fun buildNotification(callerNumber: String?, reason: AlertReason): Notification {
        val label = callerNumber ?: context.getString(R.string.takeover_alert_unknown_number)
        // Path A was tried and failed, so the Caller may be on hold with the second call.
        val mayBeOnHold = reason == AlertReason.NO_ANSWER_30S || reason == AlertReason.FAILED_THEN_ALERT
        val text = context.getString(
            if (mayBeOnHold) R.string.takeover_alert_text_hold else R.string.takeover_alert_text, label,
        )
        val live = NotificationCenter(context).liveCallPendingIntent()
        val builder = NotificationCompat.Builder(context, NotificationChannels.TAKEOVER_ALERT)
            .setSmallIcon(com.callanswer.app.R.drawable.ic_stat_call)
            .setContentTitle(context.getString(R.string.takeover_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(live)
            .addAction(
                0,
                context.getString(R.string.call_action_take_over),
                CallForegroundService.controlIntent(context, CallForegroundService.ACTION_TAKE_OVER, REQUEST_TAKE_OVER),
            )
        if (hasFullScreenIntentAccess()) builder.setFullScreenIntent(live, true)
        return builder.build()
    }

    companion object {
        const val NOTIFICATION_ID = 4601
        const val REQUEST_TAKE_OVER = 14
        const val TONE_OFFSET_MS = 5_000L
        const val TONE_INTERVAL_MS = 10_000L
        const val TONE_WINDOW_MS = 30_000L
        const val TONE_RATE = 16_000

        /** 300 ms two-step beep (880 Hz, 1100 Hz) at low amplitude, quieter than speech. */
        val TONE_PCM: ShortArray = ShortArray(TONE_RATE * 300 / 1000) { i ->
            val f = if (i < TONE_RATE * 150 / 1000) 880.0 else 1100.0
            (sin(2 * PI * f * i / TONE_RATE) * 6000).toInt().toShort()
        }
    }
}
