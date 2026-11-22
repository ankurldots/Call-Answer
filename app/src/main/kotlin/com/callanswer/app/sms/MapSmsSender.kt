package com.callanswer.app.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import com.callanswer.app.service.NotificationCenter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class MapSmsResult { DELIVERED, FAILED }

/** Dispatches one text and reports the per-part sent and delivered results back through [MapSmsSender.onResult]. */
interface MapSmsTransport {
    /** Sends [text] to [number]; returns the number of message parts. Throws when the platform refuses. */
    fun send(number: String, text: String, attemptId: String): Int
}

/**
 * Sends the Map_Location to the Caller (Requirements 3.4, 3.8). The text counts as delivered when every part
 * reports sent and delivered within [deliveryTimeoutMs] (30 s) of sending. Any other outcome (missing permission,
 * platform refusal, a failed part, no delivery report in time) posts the "text message failed" notification
 * right away, so the User hears about it within 60 s, and returns [MapSmsResult.FAILED]. The call is not affected.
 */
class MapSmsSender(
    /** Null only in JVM tests, which supply every lambda and a [transport]. */
    private val context: Context?,
    private val notifyFailure: (String) -> Unit = { NotificationCenter(context!!).mapSmsFailed(it) },
    private val permissionGranted: () -> Boolean =
        { context!!.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED },
    private val deliveryTimeoutMs: Long = DELIVERY_TIMEOUT_MS,
    private val transport: MapSmsTransport = SmsManagerTransport(context!!),
) {
    private class Attempt {
        var parts = -1
        var sentOk = 0
        var deliveredOk = 0
        val sent = CompletableDeferred<Boolean>()
        val delivered = CompletableDeferred<Boolean>()

        @Synchronized fun partsKnown(n: Int) { parts = n; evaluate() }

        @Synchronized fun record(kind: String, ok: Boolean) {
            if (!ok) {
                sent.complete(false)
                delivered.complete(false)
                return
            }
            if (kind == KIND_SENT) sentOk++ else deliveredOk++
            evaluate()
        }

        private fun evaluate() {
            if (parts < 0) return
            if (sentOk >= parts) sent.complete(true)
            if (deliveredOk >= parts) delivered.complete(true)
        }
    }

    /** Tests turn this off; the receiver is then not registered. */
    @Volatile internal var registrationEnabled = true

    private val attempts = ConcurrentHashMap<String, Attempt>()
    private val receiverLock = Any()
    private var receiver: BroadcastReceiver? = null

    suspend fun send(number: String, text: String): MapSmsResult {
        val id = UUID.randomUUID().toString()
        val attempt = Attempt()
        attempts[id] = attempt
        registerReceiver()
        val ok = try {
            if (!permissionGranted()) {
                false
            } else {
                attempt.partsKnown(transport.send(number, text, id))
                withTimeoutOrNull(deliveryTimeoutMs) { attempt.delivered.await() } == true
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "map SMS dispatch failed", t) }
            false
        } finally {
            attempts.remove(id)
            if (attempts.isEmpty()) unregisterReceiver()
        }
        if (!ok) runCatching { notifyFailure(number) }
        return if (ok) MapSmsResult.DELIVERED else MapSmsResult.FAILED
    }

    /** Result of one part: [kind] is [KIND_SENT] or [KIND_DELIVERED]. Called by the broadcast receiver. */
    fun onResult(attemptId: String, kind: String, ok: Boolean) {
        attempts[attemptId]?.record(kind, ok)
    }

    private fun registerReceiver() {
        synchronized(receiverLock) {
        if (receiver != null || !registrationEnabled) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getStringExtra(EXTRA_ATTEMPT) ?: return
                val kind = intent.getStringExtra(EXTRA_KIND) ?: return
                onResult(id, kind, resultCode == Activity.RESULT_OK)
            }
        }
        val filter = IntentFilter(ACTION_RESULT)
        val app = context!!.applicationContext
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(r, filter)
        receiver = r
        }
    }

    private fun unregisterReceiver() {
        synchronized(receiverLock) {
            receiver?.let { runCatching { context!!.applicationContext.unregisterReceiver(it) } }
            receiver = null
        }
    }

    companion object {
        const val ACTION_RESULT = "com.callanswer.app.action.MAP_SMS_RESULT"
        const val EXTRA_ATTEMPT = "attemptId"
        const val EXTRA_KIND = "kind"
        const val KIND_SENT = "sent"
        const val KIND_DELIVERED = "delivered"
        const val DELIVERY_TIMEOUT_MS = 30_000L
        private const val TAG = "MapSmsSender"
    }
}

/** [SmsManager] with one sent and one delivered [PendingIntent] per message part. */
class SmsManagerTransport(private val context: Context) : MapSmsTransport {
    private val requestCodes = AtomicInteger(0x5000)

    override fun send(number: String, text: String, attemptId: String): Int {
        val app = context.applicationContext
        val sms = app.getSystemService(SmsManager::class.java)
            ?: throw IllegalStateException("no SmsManager")
        val parts = ArrayList(sms.divideMessage(text))
        val sent = ArrayList<PendingIntent>()
        val delivered = ArrayList<PendingIntent>()
        for (i in parts.indices) {
            sent += resultIntent(app, attemptId, MapSmsSender.KIND_SENT, i)
            delivered += resultIntent(app, attemptId, MapSmsSender.KIND_DELIVERED, i)
        }
        sms.sendMultipartTextMessage(number, null, parts, sent, delivered)
        return parts.size
    }

    private fun resultIntent(app: Context, attemptId: String, kind: String, part: Int): PendingIntent {
        val intent = Intent(MapSmsSender.ACTION_RESULT).setPackage(app.packageName)
            .putExtra(MapSmsSender.EXTRA_ATTEMPT, attemptId)
            .putExtra(MapSmsSender.EXTRA_KIND, kind)
            .putExtra("partIndex", part)
        return PendingIntent.getBroadcast(
            app, requestCodes.incrementAndGet(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
    }
}
