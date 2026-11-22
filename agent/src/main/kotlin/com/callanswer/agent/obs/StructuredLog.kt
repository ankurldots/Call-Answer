package com.callanswer.agent.obs

import com.callanswer.agent.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * JSON log lines with call id, event name, duration and status only. There is no free-text field,
 * so the Caller number, transcript and Protected_Terms have no way into a line.
 */
class StructuredLog(
    private val clock: Clock,
    private val out: (String) -> Unit = { System.out.println(it) },
) {
    fun log(callId: String, userId: String, e: TraceEvent) {
        out(
            line(
                buildJsonObject {
                    put("ts", JsonPrimitive(clock.nowMillis()))
                    put("callId", JsonPrimitive(Codes.sanitize(callId)))
                    put("userId", JsonPrimitive(Codes.sanitize(userId)))
                    put("event", JsonPrimitive(e.event.name))
                    put("component", JsonPrimitive(e.component))
                    put("status", JsonPrimitive(e.status))
                    e.durationMs?.let { put("durationMs", JsonPrimitive(it)) }
                    e.errorCode?.let { put("errorCode", JsonPrimitive(it)) }
                },
            ),
        )
    }

    /** A line not tied to a trace event, for example `SERVER_START`. */
    fun log(callId: String?, event: String, status: String, durationMs: Long? = null) {
        out(
            line(
                buildJsonObject {
                    put("ts", JsonPrimitive(clock.nowMillis()))
                    callId?.let { put("callId", JsonPrimitive(Codes.sanitize(it))) }
                    put("event", JsonPrimitive(Codes.sanitize(event)))
                    put("status", JsonPrimitive(Codes.sanitize(status)))
                    durationMs?.let { put("durationMs", JsonPrimitive(it)) }
                },
            ),
        )
    }

    private fun line(o: JsonObject): String = Json.encodeToString(JsonObject.serializer(), o)
}
