package com.callanswer.app.sms

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Result of one Map_SMS request, as reported to the server (Requirement 10.13, 10.14). */
enum class MapSmsResult { SENT, FAILED, NO_PERMISSION }

/** Hands one text to the platform. Returns the number of message parts. Throws when the platform refuses. */
interface SmsTransport {
    /** [subscriptionId] is negative when the platform has no default SMS subscription. */
    fun send(subscriptionId: Int, number: String, text: String, attemptId: String): Int
}

/** Reads the SIM subscriptions. */
interface SubscriptionSource {
    /** `SubscriptionManager.getDefaultSmsSubscriptionId()`; needs no permission. */
    fun defaultSmsSubscriptionId(): Int

    /** Ids of the active subscriptions, or null when they cannot be read (no `READ_PHONE_STATE`). */
    fun activeSubscriptionIds(): List<Int>?
}

/** Receives the result of one Map_SMS (the reporter of task 12.17). */
fun interface MapSmsResultSink {
    suspend fun report(callId: String, result: MapSmsResult)
}

/**
 * Sends the Map_SMS text of a `send_map_sms` push from the User's own SIM with `SmsManager` (Requirement 10.13 to 10.15).
 *
 * Result: [MapSmsResult.NO_PERMISSION] without `SEND_SMS` (nothing is sent), [MapSmsResult.SENT] when every message part
 * reports "sent", [MapSmsResult.FAILED] for a platform refusal, a failed part, or no report within [sentTimeoutMs]. The
 * delivery report of a part goes to the local log only. A repeated `callId` is ignored. The number and the text are
 * never logged.
 */
class MapSmsSender(
    private val permissionGranted: () -> Boolean,
    private val transport: SmsTransport,
    private val subscriptions: SubscriptionSource,
    /** The SIM the User picked in the settings, or null for the default SMS subscription. */
    private val selectedSubscriptionId: () -> Int?,
    /** True the first time a `callId` is seen; false for a repeat. */
    private val claim: (String) -> Boolean,
    private val sink: MapSmsResultSink,
    private val sentTimeoutMs: Long = SENT_TIMEOUT_MS,
) {
    private class Attempt {
        private var parts = -1
        private var sentOk = 0
        val sent = CompletableDeferred<Boolean>()

        @Synchronized fun partsKnown(n: Int) { parts = n; evaluate() }

        @Synchronized fun partSent(ok: Boolean) {
            if (!ok) sent.complete(false) else { sentOk++; evaluate() }
        }

        private fun evaluate() { if (parts in 0..sentOk) sent.complete(true) }
    }

    private val attempts = ConcurrentHashMap<String, Attempt>()

    /**
     * Entry point for the push handler (task 12.7): sends [text] to [number] for [callId] and reports the result.
     * Returns the result, or null when [callId] was handled before (nothing is sent or reported again).
     */
    suspend fun onSendMapSms(callId: String, number: String, text: String): MapSmsResult? {
        if (callId.isBlank() || !claim(callId)) return null
        val result = send(number, text)
        try {
            sink.report(callId, result)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "map SMS result not reported: ${t.javaClass.simpleName}") }
        }
        return result
    }

    /** The subscription to send from: the User's choice while it is active, otherwise the default SMS subscription. */
    fun resolveSubscriptionId(): Int {
        val selected = selectedSubscriptionId()
        if (selected != null && subscriptions.activeSubscriptionIds()?.contains(selected) == true) return selected
        return subscriptions.defaultSmsSubscriptionId()
    }

    private suspend fun send(number: String, text: String): MapSmsResult {
        if (!permissionGranted()) return MapSmsResult.NO_PERMISSION
        if (number.isBlank() || text.isBlank()) return MapSmsResult.FAILED
        val id = UUID.randomUUID().toString()
        val attempt = Attempt()
        attempts[id] = attempt
        return try {
            attempt.partsKnown(transport.send(resolveSubscriptionId(), number, text, id))
            val ok = withTimeoutOrNull(sentTimeoutMs) { attempt.sent.await() } == true
            if (ok) MapSmsResult.SENT else MapSmsResult.FAILED
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "map SMS dispatch failed: ${t.javaClass.simpleName}") }
            MapSmsResult.FAILED
        } finally {
            attempts.remove(id)
        }
    }

    /** "Sent" report of one part, from the broadcast receiver. */
    fun onPartSent(attemptId: String, ok: Boolean) { attempts[attemptId]?.partSent(ok) }

    /** "Delivered" report of one part. Local log only (Requirement 10.13); it changes no result. */
    fun onPartDelivered(ok: Boolean) {
        runCatching { Log.i(TAG, "map SMS delivery report: ${if (ok) "delivered" else "not delivered"}") }
    }

    companion object {
        const val SENT_TIMEOUT_MS = 30_000L
        private const val TAG = "MapSmsSender"
    }
}
