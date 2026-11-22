package com.callanswer.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** S2 comparison: a plain (non-accessibility) foreground service that owns the AudioRecord. */
class S2CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SpikeLog.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("s2", "S2 capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "s2")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("S2 capture (foreground service)")
            .build()
        try {
            startForeground(2002, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Throwable) {
            SpikeLog.log("S2", "startForeground failed: $e")
            stopSelf(); return START_NOT_STICKY
        }
        val seconds = intent?.getIntExtra(EXTRA_SECONDS, 10) ?: 10
        val delay = intent?.getIntExtra(EXTRA_DELAY, 0) ?: 0
        Thread {
            Thread.sleep(delay * 1000L)
            S2Capture.runAll(this, S2Owner.FOREGROUND_SERVICE, seconds)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_SECONDS = "seconds"
        const val EXTRA_DELAY = "delay"
    }
}
