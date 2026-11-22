package com.callanswer.spike

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock

/** Foreground service (type mediaPlayback). S1 logs the start result and notification post time. */
class SpikeForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SpikeLog.init(this)
        if (intent?.action == S1Spike.ACTION_END_CALL) {
            S1Spike.endCall(this, "notification action")
            return START_NOT_STICKY
        }
        if (intent?.action == S6Alert.ACTION_TAKE_OVER) {
            SpikeLog.log(S6Alert.TAG, "B Take over action reached SpikeForegroundService (sinceRing=n/a)")
            S6Alert.cancel(this)
            return START_NOT_STICKY
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            S1Spike.ensureChannel(this)
            val endPi = PendingIntent.getService(
                this, 2, Intent(this, SpikeForegroundService::class.java).setAction(S1Spike.ACTION_END_CALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(this, S1Spike.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Spike S1: call in progress")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "End call", endPi).build())
                .build()
            startForeground(S1Spike.NOTIF_ID_FGS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            SpikeLog.log(
                S1Spike.CHECK,
                "startForeground ok; notification posted ${SystemClock.elapsedRealtime() - t0} ms after onStartCommand, sinceRing=${S1Spike.sinceRing()} ms",
            )
        } catch (e: Exception) {
            SpikeLog.log(S1Spike.CHECK, "startForeground FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
        return START_NOT_STICKY
    }
}
