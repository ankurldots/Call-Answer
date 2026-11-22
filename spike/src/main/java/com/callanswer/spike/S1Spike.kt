package com.callanswer.spike

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyManager

/** Spike S1 logic: answer a screened call, start the foreground service, end the call. */
object S1Spike {
    const val CHECK = "S1"
    const val CHANNEL_ID = "spike_s1_call"
    const val NOTIF_ID_FGS = 4101
    const val NOTIF_ID_FALLBACK = 4102
    const val ACTION_END_CALL = "com.callanswer.spike.S1_END_CALL"
    const val EXTRA_S1_START_FGS = "s1_start_fgs"
    private const val RETRY_INTERVAL_MS = 100L
    private const val RETRY_LIMIT_MS = 2000L

    private val main = Handler(Looper.getMainLooper())

    @Volatile var ringElapsed: Long = 0L
        private set

    fun markRing() { ringElapsed = SystemClock.elapsedRealtime() }

    fun sinceRing(): Long = SystemClock.elapsedRealtime() - ringElapsed

    private fun has(ctx: Context, perm: String) =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Spike S1 call", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    /** Starts the foreground service from the screening callback; falls back to a full-screen-intent notification. */
    fun startForeground(ctx: Context) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            ctx.startForegroundService(Intent(ctx, SpikeForegroundService::class.java))
            SpikeLog.log(CHECK, "startForegroundService accepted after ${SystemClock.elapsedRealtime() - t0} ms (sinceRing=${sinceRing()} ms)")
        } catch (e: Exception) {
            val kind = if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                "ForegroundServiceStartNotAllowedException"
            } else {
                e.javaClass.simpleName
            }
            SpikeLog.log(CHECK, "startForegroundService REFUSED: $kind: ${e.message}; using full-screen-intent fallback")
            postFallback(ctx)
        }
    }

    private fun postFallback(ctx: Context) {
        try {
            ensureChannel(ctx)
            val launch = Intent(ctx, CheckActivity::class.java).apply {
                putExtra(CheckActivity.EXTRA_CHECK, CHECK)
                putExtra(EXTRA_S1_START_FGS, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pi = PendingIntent.getActivity(ctx, 1, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Spike S1 fallback")
                .setContentText("Opening activity to start the foreground service")
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .build()
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val canFsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
            nm.notify(NOTIF_ID_FALLBACK, n)
            SpikeLog.log(CHECK, "fallback full-screen-intent notification posted at sinceRing=${sinceRing()} ms (canUseFullScreenIntent=$canFsi)")
        } catch (e: Exception) {
            SpikeLog.log(CHECK, "fallback notification FAILED: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun callState(ctx: Context): Int? = try {
        @Suppress("DEPRECATION")
        ctx.getSystemService(TelephonyManager::class.java).callState
    } catch (e: SecurityException) {
        null
    }

    /** Calls acceptRingingCall every 100 ms for up to 2 s, stopping once the phone state is OFFHOOK. */
    fun answerWithRetries(ctx: Context) {
        val app = ctx.applicationContext
        if (!has(app, Manifest.permission.ANSWER_PHONE_CALLS)) {
            SpikeLog.log(CHECK, "ANSWER_PHONE_CALLS not granted; cannot answer")
            return
        }
        val tm = app.getSystemService(TelecomManager::class.java)
        val start = SystemClock.elapsedRealtime()
        var attempt = 0
        val task = object : Runnable {
            override fun run() {
                val state = callState(app)
                if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    SpikeLog.log(CHECK, "answered: OFFHOOK observed after $attempt attempts, ring-to-answer=${sinceRing()} ms")
                    return
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                if (elapsed > RETRY_LIMIT_MS) {
                    SpikeLog.log(CHECK, "answer FAILED: no OFFHOOK within ${RETRY_LIMIT_MS} ms after $attempt attempts (state=$state)")
                    return
                }
                attempt++
                try {
                    @Suppress("MissingPermission")
                    tm.acceptRingingCall()
                    SpikeLog.log(CHECK, "acceptRingingCall attempt $attempt at +$elapsed ms (callState=$state)")
                } catch (e: Exception) {
                    SpikeLog.log(CHECK, "acceptRingingCall attempt $attempt threw ${e.javaClass.simpleName}: ${e.message}")
                }
                main.postDelayed(this, RETRY_INTERVAL_MS)
            }
        }
        main.post(task)
    }

    /** Ends the active call with TelecomManager.endCall() and logs the result. */
    @Suppress("DEPRECATION")
    fun endCall(ctx: Context, reason: String) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val ok = ctx.getSystemService(TelecomManager::class.java).endCall()
            SpikeLog.log(CHECK, "endCall() ($reason) returned $ok in ${SystemClock.elapsedRealtime() - t0} ms")
        } catch (e: Exception) {
            SpikeLog.log(CHECK, "endCall() ($reason) threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun scheduleAutoEnd(ctx: Context) {
        val app = ctx.applicationContext
        val delayMs = S1Settings.endDelaySeconds(app) * 1000L
        main.postDelayed({ endCall(app, "auto after $delayMs ms") }, delayMs)
    }
}
