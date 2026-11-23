package com.callanswer.agent.notify

import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.obs.TraceEventName
import com.callanswer.core.model.Language
import com.callanswer.core.tenant.UserId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** The Map_SMS_Result the Companion_App reports through `POST /calls/{id}/map-sms-result`. */
enum class MapSmsStatus { SENT, FAILED, NO_PERMISSION }

/** Why `send_map_sms` did not end in `SENT`. The names are the `reason` values of the tool result. */
enum class MapSmsReason {
    SKIPPED_UNKNOWN_NUMBER, SKIPPED_NOT_MOBILE, NOT_CONFIGURED, FAILED, NO_PERMISSION, NO_ACK, NO_DEVICE, ALREADY_SENT
}

/** Outcome of [MapSmsService.sendAndAwait]. */
sealed interface MapSmsOutcome {
    data object Sent : MapSmsOutcome
    data object AlreadySent : MapSmsOutcome
    /** Sonic must say the location verbally. */
    data class SayVerbally(val reason: MapSmsReason) : MapSmsOutcome
}

/** Pushes one message to every device of a User. [AlertService] is the production implementation. */
fun interface PushSender {
    suspend fun send(userId: UserId, message: PushMessage, trace: CallTrace?): PushOutcome
}

/** Stores a reason or a result in the Call_Log entry of the call (Requirements 10.7, 10.16). */
fun interface MapSmsRecorder {
    fun record(userId: UserId, callId: String, value: String)
}

/**
 * Push-and-await of the Map_SMS (Requirements 10.5, 10.7, 10.8, 10.10, 10.12, 10.16). The server sends no SMS and uses
 * no SMS gateway: it pushes the Map_SMS_Request (`send_map_sms`) to the devices of the call owner and waits
 * [ackWindowMs] for the Map_SMS_Result, which `POST /calls/{id}/map-sms-result` hands over through [complete].
 */
class MapSmsService(
    private val push: PushSender,
    private val ackWindowMs: Long = ACK_WINDOW_MS,
    private val recorder: MapSmsRecorder = MapSmsRecorder { _, _, _ -> },
) {
    constructor(
        alerts: AlertService,
        ackWindowMs: Long = ACK_WINDOW_MS,
        recorder: MapSmsRecorder = MapSmsRecorder { _, _, _ -> },
    ) : this({ u, m, t -> alerts.send(u, m, t) }, ackWindowMs, recorder)

    private val pending = ConcurrentHashMap<Pair<String, String>, CompletableDeferred<MapSmsStatus>>()
    private val attempted = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    /**
     * @param callerNumber E.164 of the Caller from the call state, or null when hidden. Never taken from the model.
     * @param mapLocation the User's Map_Location from the frozen settings.
     */
    suspend fun sendAndAwait(
        userId: UserId,
        callId: String,
        callerNumber: String?,
        mapLocation: String?,
        language: Language,
        trace: CallTrace? = null,
    ): MapSmsOutcome {
        val key = userId.sub to callId
        val number = callerNumber?.trim()?.takeIf { it.isNotEmpty() }
        val location = mapLocation?.trim()?.takeIf { it.isNotEmpty() }
        val skip = when {
            number == null -> MapSmsReason.SKIPPED_UNKNOWN_NUMBER
            !isMobile(number) -> MapSmsReason.SKIPPED_NOT_MOBILE
            location == null -> MapSmsReason.NOT_CONFIGURED
            else -> null
        }
        if (skip != null) {
            record(userId, callId, skip.name, trace)
            return MapSmsOutcome.SayVerbally(skip)
        }
        // At most once per call: the first eligible attempt takes the slot.
        if (!attempted.add(key)) return MapSmsOutcome.AlreadySent

        val ack = CompletableDeferred<MapSmsStatus>()
        pending[key] = ack
        try {
            val text = render(location!!, language)
            val outcome = push.send(userId, PushMessages.sendMapSms(callId, number!!, text), trace)
            if (!outcome.delivered) {
                val reason = if (outcome.failure == "NO_DEVICE") MapSmsReason.NO_DEVICE else MapSmsReason.FAILED
                return failed(userId, callId, reason, trace)
            }
            val status = withTimeoutOrNull(ackWindowMs) { ack.await() }
                ?: return failed(userId, callId, MapSmsReason.NO_ACK, trace)
            record(userId, callId, status.name, trace)
            return when (status) {
                MapSmsStatus.SENT -> MapSmsOutcome.Sent
                MapSmsStatus.FAILED -> failed(userId, callId, MapSmsReason.FAILED, trace, recordIt = false)
                MapSmsStatus.NO_PERMISSION -> failed(userId, callId, MapSmsReason.NO_PERMISSION, trace, recordIt = false)
            }
        } finally {
            pending.remove(key, ack)
        }
    }

    /**
     * Called by `POST /calls/{id}/map-sms-result` with the `sub` of the token. Returns true for the first result of a
     * call that is waiting; a second result, a late result, or a foreign User's call identifier returns false.
     */
    fun complete(userId: UserId, callId: String, status: MapSmsStatus): Boolean =
        pending[userId.sub to callId]?.complete(status) ?: false

    /** True while a Map_SMS_Request of the call waits for its result. */
    fun isPending(userId: UserId, callId: String): Boolean = pending.containsKey(userId.sub to callId)

    private suspend fun failed(
        userId: UserId,
        callId: String,
        reason: MapSmsReason,
        trace: CallTrace?,
        recordIt: Boolean = true,
    ): MapSmsOutcome {
        if (recordIt) record(userId, callId, reason.name, trace)
        // The alert is best effort; the call stays open either way.
        push.send(userId, PushMessages.mapSmsFailed(callId, reason.name), trace)
        return MapSmsOutcome.SayVerbally(reason)
    }

    private fun record(userId: UserId, callId: String, value: String, trace: CallTrace?) {
        try {
            recorder.record(userId, callId, value)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            trace?.recordError("map_sms_record", "RECORD_FAILED")
        }
        trace?.record(TraceEventName.TOOL_CALL, "send_map_sms", value)
    }

    companion object {
        const val ACK_WINDOW_MS = 20_000L

        private val INDIA_MOBILE = Regex("""\+91[6-9][0-9]{9}""")
        private val E164 = Regex("""\+[1-9][0-9]{7,14}""")

        /**
         * Indian numbers must be 10-digit mobile numbers (start 6 to 9). Numbers of other countries are accepted when
         * they are valid E.164, because their line type cannot be derived from the digits.
         */
        fun isMobile(e164: String): Boolean =
            if (e164.startsWith("+91")) INDIA_MOBILE.matches(e164) else E164.matches(e164)

        /**
         * Fixed template in the Caller's language. [Language] only has `hi` and `en`; the caller of the service passes
         * the default language when the Caller speaks another one.
         */
        fun render(mapLocation: String, language: Language): String = template(language, mapLocation)

        private fun template(language: Language, location: String): String = when (language) {
            Language.EN -> "Directions to our home: $location"
            Language.HI -> "हमारे घर का रास्ता: $location"
        }
    }
}
