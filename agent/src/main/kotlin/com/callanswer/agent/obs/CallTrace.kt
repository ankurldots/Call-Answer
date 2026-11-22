package com.callanswer.agent.obs

import com.callanswer.agent.Clock
import kotlinx.serialization.Serializable

/** Events of Requirement 23 criterion 2, plus ERROR for criterion 4. */
enum class TraceEventName {
    VOICE_STREAM_START,
    SONIC_SESSION_START,
    FIRST_CALLER_AUDIO,
    FIRST_AGENT_AUDIO,
    TOOL_CALL,
    BARGE_IN,
    SONIC_SESSION_SWITCH,
    FALLBACK,
    TAKEOVER_ALERT,
    CALL_END,
    ERROR,
}

/**
 * One trace entry. Holds only codes and numbers. `status`, `component` and `errorCode` are
 * restricted to [Codes.isSafe], so caller numbers, transcript text and Protected_Terms cannot be stored.
 */
@Serializable
data class TraceEvent(
    val tMs: Long,
    val event: TraceEventName,
    val component: String,
    val status: String,
    val durationMs: Long? = null,
    val errorCode: String? = null,
)

object Codes {
    private val SAFE = Regex("[A-Za-z0-9_.:-]{1,48}")
    private val DIGITS = Regex("\\d{6,}")

    /** A short identifier made of letters, digits and `_.:-`, with no run of 6 or more digits (phone numbers). */
    fun isSafe(value: String): Boolean = SAFE.matches(value) && !DIGITS.containsMatchIn(value)

    /** Returns [value] when safe, otherwise the placeholder "INVALID". */
    fun sanitize(value: String): String = if (isSafe(value)) value else "INVALID"
}

/** Persists trace events (DynamoDB item `TRACE#<callId>` in production, a fake in tests). */
interface TraceSink {
    fun write(userId: String, callId: String, events: List<TraceEvent>, ttlEpochSeconds: Long)
}

/** Per-call trace. Event times are milliseconds since the call started. Thread-safe. */
class CallTrace(
    val userId: String,
    val callId: String,
    private val clock: Clock,
    private val log: StructuredLog? = null,
    private val startMs: Long = clock.nowMillis(),
) {
    private val events = ArrayList<TraceEvent>()

    fun record(
        event: TraceEventName,
        component: String,
        status: String = "OK",
        durationMs: Long? = null,
        errorCode: String? = null,
    ): TraceEvent {
        val e = TraceEvent(
            tMs = clock.nowMillis() - startMs,
            event = event,
            component = Codes.sanitize(component),
            status = Codes.sanitize(status),
            durationMs = durationMs?.coerceAtLeast(0),
            errorCode = errorCode?.let(Codes::sanitize),
        )
        synchronized(events) { events.add(e) }
        log?.log(callId, userId, e)
        return e
    }

    /** Error of a Sonic_Session or an Agent_Tool call: code and component (criterion 4). */
    fun recordError(component: String, errorCode: String, durationMs: Long? = null): TraceEvent =
        record(TraceEventName.ERROR, component, "ERROR", durationMs, errorCode)

    fun events(): List<TraceEvent> = synchronized(events) { events.toList() }

    /** Expiry time for the DynamoDB `ttl` attribute: start time plus 30 days, in epoch seconds. */
    fun ttlEpochSeconds(): Long = (startMs + TTL_MILLIS) / 1000

    fun persist(sink: TraceSink) = sink.write(userId, callId, events(), ttlEpochSeconds())

    companion object {
        const val TTL_DAYS = 30L
        const val TTL_MILLIS = TTL_DAYS * 24 * 60 * 60 * 1000
    }
}
