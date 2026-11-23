package com.callanswer.agent.notify

import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.obs.TraceEventName
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.UserId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Result of a push to all devices of one User. */
data class PushOutcome(
    val devices: Int,
    val accepted: Int,
    val removed: Int,
    /** Short code of the first failure ("NO_DEVICE", "FCM_HTTP_500", ...), or null when at least one device accepted. */
    val failure: String?,
) {
    val delivered: Boolean get() = accepted > 0
}

/** Status the `request_takeover` tool returns for the push part. */
enum class TakeoverAlertStatus { ALERT_SENT, ALERT_UNDELIVERED }

/**
 * Sends notifications to every stored device of the User who owns the call, and to no other device. A device token
 * that FCM reports as unregistered or invalid is deleted. There is no SMS fallback (Requirement 30).
 *
 * The Takeover_Alert waits [ackTimeoutMs] for `POST /calls/{id}/alert-ack` ([acknowledge]). With no device, an FCM
 * rejection of every device, or no acknowledgment, the failure goes to the trace and the status is
 * [TakeoverAlertStatus.ALERT_UNDELIVERED], so that the Agent takes the nobody-available path.
 */
class AlertService(
    private val fcm: FcmClient,
    private val repoFor: (UserId) -> UserScopedRepository,
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
) {
    private val pending = ConcurrentHashMap<Pair<String, String>, CompletableDeferred<Unit>>()

    /** Pushes [message] to every device of [userId]. Failures are recorded in [trace] and never thrown. */
    suspend fun send(userId: UserId, message: PushMessage, trace: CallTrace? = null): PushOutcome {
        val repo = repoFor(userId)
        val devices = try {
            repo.listDevices()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(trace, message, PushOutcome(0, 0, 0, "DEVICE_LIST_FAILED"))
        }
        if (devices.isEmpty()) return fail(trace, message, PushOutcome(0, 0, 0, "NO_DEVICE"))
        var accepted = 0
        var removed = 0
        var failure: String? = null
        for (d in devices) {
            when (val r = fcm.send(d.fcmToken, message)) {
                FcmResult.Accepted -> accepted++
                is FcmResult.TokenInvalid -> {
                    failure = failure ?: r.code
                    try {
                        repo.deleteDevice(d.deviceId)
                        removed++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("device_remove_failed user=${userId.sub}")
                    }
                }
                is FcmResult.Failed -> failure = failure ?: r.code
            }
        }
        val outcome = PushOutcome(devices.size, accepted, removed, if (accepted > 0) null else failure)
        return if (outcome.delivered) outcome else fail(trace, message, outcome)
    }

    /** Sends the data-only `recent_caller` message (number, endMs, intent, callId) to every device of [userId]. */
    suspend fun sendRecentCaller(
        userId: UserId, number: String, endMs: Long, intent: com.callanswer.core.model.IntentCategory, callId: String,
        trace: CallTrace? = null,
    ): PushOutcome = send(userId, PushMessages.recentCaller(number, endMs, intent.name, callId), trace)

    /** Sends the Takeover_Alert and waits for an acknowledgment of any device of the owner. */
    suspend fun sendTakeoverAlert(userId: UserId, message: PushMessage, callId: String, trace: CallTrace? = null): TakeoverAlertStatus {
        require(message.type == PushType.TAKEOVER_ALERT) { "Not a takeover alert" }
        val key = userId.sub to callId
        val ack = pending.computeIfAbsent(key) { CompletableDeferred() }
        try {
            val outcome = send(userId, message, trace)
            if (!outcome.delivered) return TakeoverAlertStatus.ALERT_UNDELIVERED
            val acked = withTimeoutOrNull(ackTimeoutMs) { ack.await(); true } ?: false
            if (!acked) {
                trace?.record(TraceEventName.TAKEOVER_ALERT, "alert", "UNDELIVERED", errorCode = "NO_ACK")
                return TakeoverAlertStatus.ALERT_UNDELIVERED
            }
            trace?.record(TraceEventName.TAKEOVER_ALERT, "alert", "ACKED")
            return TakeoverAlertStatus.ALERT_SENT
        } finally {
            pending.remove(key)
        }
    }

    /**
     * Called by `POST /calls/{id}/alert-ack` with the `sub` of the token. Returns true when the User has a pending
     * alert for the call; a foreign User's call identifier matches nothing.
     */
    fun acknowledge(userId: UserId, callId: String): Boolean = pending[userId.sub to callId]?.complete(Unit) ?: false

    private fun fail(trace: CallTrace?, message: PushMessage, outcome: PushOutcome): PushOutcome {
        val code = outcome.failure ?: "UNDELIVERED"
        if (message.type == PushType.TAKEOVER_ALERT) {
            trace?.record(TraceEventName.TAKEOVER_ALERT, "alert", "UNDELIVERED", errorCode = code)
        } else {
            trace?.recordError("push_${message.type.wire}", code)
        }
        return outcome
    }

    companion object {
        const val ACK_TIMEOUT_MS = 10_000L
    }
}
