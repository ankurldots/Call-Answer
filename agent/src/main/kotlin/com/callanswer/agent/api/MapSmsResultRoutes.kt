package com.callanswer.agent.api

import com.callanswer.agent.notify.AlertService
import com.callanswer.agent.notify.MapSmsRecorder
import com.callanswer.agent.notify.MapSmsService
import com.callanswer.agent.notify.MapSmsStatus
import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.obs.TraceEventName
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.UserId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

private const val MAX_CALL_ID_LENGTH = 128

/**
 * What `POST /calls/{id}/map-sms-result` needs.
 *
 * [recorder] stores the result in the Call_Log entry of the call; the route calls it once per call, for the first
 * result, also when that result arrives after the wait of [MapSmsService] ended. Do not pass the same recorder to
 * [MapSmsService], or the result is recorded twice. [traceFor] returns the live trace of the call, or null once the
 * call is over. [isLiveCall] tells whether the call of the User is in progress (its Call_Log entry does not exist yet).
 */
class MapSmsResultRouteDeps(
    val repoFor: (UserId) -> UserScopedRepository,
    val mapSms: MapSmsService,
    val recorder: MapSmsRecorder,
    val traceFor: (UserId, String) -> CallTrace? = { _, _ -> null },
    val isLiveCall: (UserId, String) -> Boolean = { _, _ -> false },
) {
    internal val seen: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()
}

private suspend fun ApplicationCall.status400(field: String, message: String) = respondText(
    buildJsonObject { put("field", field); put("message", message) }.toString(),
    ContentType.Application.Json, HttpStatusCode.BadRequest,
)

private suspend fun ownsCall(deps: MapSmsResultRouteDeps, userId: UserId, callId: String): Boolean {
    if (callId.isEmpty() || callId.length > MAX_CALL_ID_LENGTH) return false
    if (deps.mapSms.isPending(userId, callId) || deps.isLiveCall(userId, callId)) return true
    return try {
        deps.repoFor(userId).findCall(callId) != null
    } catch (e: IllegalArgumentException) {
        false
    }
}

/**
 * Mounts `POST /calls/{id}/map-sms-result` with `{status: SENT|FAILED|NO_PERMISSION}`. The User is the token's `sub`
 * only; a call identifier of another User answers 404, an invalid status 400. The first result of a call is stored in
 * the Call_Log and the trace and hands over to the waiting `send_map_sms`; a second result is ignored (204).
 */
fun Route.mapSmsResultRoutes(deps: MapSmsResultRouteDeps) {
    post("/calls/{id}/map-sms-result") {
        val userId = UserId(call.caller.userId)
        val callId = call.parameters["id"].orEmpty()
        val body = try {
            Json.parseToJsonElement(call.receiveText()).jsonObject
        } catch (e: SerializationException) {
            return@post call.status400("body", "malformed JSON")
        } catch (e: IllegalArgumentException) {
            return@post call.status400("body", "malformed JSON")
        }
        val status = (body["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { s -> MapSmsStatus.entries.firstOrNull { it.name == s } }
            ?: return@post call.status400("status", "SENT, FAILED, or NO_PERMISSION is required")
        if (!ownsCall(deps, userId, callId)) return@post call.respondError(HttpStatusCode.NotFound, "not_found")

        if (!deps.seen.add(userId.sub to callId)) return@post call.respond(HttpStatusCode.NoContent)
        // Wake the waiting tool first; false for a late result, which is stored all the same.
        val accepted = deps.mapSms.complete(userId, callId, status)
        try {
            deps.recorder.record(userId, callId, status.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deps.traceFor(userId, callId)?.recordError("map_sms_result", "RECORD_FAILED")
        }
        deps.traceFor(userId, callId)?.record(
            TraceEventName.TOOL_CALL, "map_sms_result", status.name, errorCode = if (accepted) null else "LATE",
        )
        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Mounts `POST /calls/{id}/alert-ack`: the app confirms a Takeover_Alert. 204 when the User's own pending alert for
 * the call was acknowledged; 404 when there is none (also for another User's call identifier).
 */
fun Route.alertAckRoutes(alerts: AlertService) {
    post("/calls/{id}/alert-ack") {
        val acked = alerts.acknowledge(UserId(call.caller.userId), call.parameters["id"].orEmpty())
        if (acked) call.respond(HttpStatusCode.NoContent) else call.respondError(HttpStatusCode.NotFound, "not_found")
    }
}
