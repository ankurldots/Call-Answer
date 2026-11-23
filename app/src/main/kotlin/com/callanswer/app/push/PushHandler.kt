package com.callanswer.app.push

import android.util.Log
import com.callanswer.app.api.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Notifications the push messages raise. */
interface PushNotifier {
    fun showTakeoverAlert(callId: String, number: String?)
    fun showCallStarted(callId: String, number: String?)
    /** Removes the live-call notification and the Takeover_Alert of [callId]. */
    fun clearCall(callId: String)
}

/**
 * Acts on one parsed push message. The Takeover_Alert is shown first and acknowledged with `alert-ack` within
 * [ACK_BUDGET_MS] (one retry inside that time). A failure of one message never reaches the caller.
 */
class PushHandler(
    private val notifier: PushNotifier,
    private val alertAck: suspend (callId: String) -> Unit,
    private val sendMapSms: suspend (callId: String, number: String, text: String) -> Unit,
    /** Stores the record in `RecentCallCache` (see `RecentCallerPush`); no notification is shown. */
    private val recentCaller: suspend (data: Map<String, String>) -> Unit = {},
) {
    suspend fun handle(message: PushMessage) {
        try {
            when (message) {
                is PushMessage.TakeoverAlert -> {
                    notifier.showTakeoverAlert(message.callId, message.number)
                    acknowledge(message.callId)
                }
                is PushMessage.CallStarted -> notifier.showCallStarted(message.callId, message.number)
                is PushMessage.CallEnded -> notifier.clearCall(message.callId)
                is PushMessage.SendMapSms -> sendMapSms(message.callId, message.number, message.text)
                is PushMessage.RecentCaller -> recentCaller(message.data)
                is PushMessage.Ignored -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "push handling failed: ${t.javaClass.simpleName}") }
        }
    }

    private suspend fun acknowledge(callId: String) {
        withTimeoutOrNull(ACK_BUDGET_MS) {
            for (attempt in 1..2) {
                try {
                    alertAck(callId)
                    return@withTimeoutOrNull
                } catch (e: ApiException) {
                    if (attempt == 2) runCatching { Log.w(TAG, "alert-ack failed: ${e.javaClass.simpleName}") }
                }
            }
        } ?: Unit
    }

    companion object {
        const val ACK_BUDGET_MS = 5_000L
        private const val TAG = "PushHandler"
    }
}
