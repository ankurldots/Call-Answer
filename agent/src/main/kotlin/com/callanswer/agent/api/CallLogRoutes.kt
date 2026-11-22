package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.store.ItemKey
import com.callanswer.agent.store.RecordingStore
import com.callanswer.agent.store.RetentionSweeper
import com.callanswer.agent.store.SettingsStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.ReportStatus
import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.TableKeys
import com.callanswer.core.tenant.UserId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64

private const val DEFAULT_PAGE = 50
private const val MAX_CALL_ID_LENGTH = 128
private const val MAX_CURSOR_LENGTH = 256

/** The stores behind the call log routes. [sweeper] removes a call with its recording, transcript, and trace. */
class CallLogRouteDeps(
    val repoFor: (UserId) -> UserScopedRepository,
    val settings: SettingsStore,
    val recordings: RecordingStore,
    val sweeper: RetentionSweeper,
    val clock: Clock,
    val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
)

private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

private fun ApplicationCall.userId(): UserId = UserId(caller.userId)

private suspend fun ApplicationCall.json(status: HttpStatusCode, body: JsonElement) =
    respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.invalid(field: String, message: String = "invalid value") =
    json(HttpStatusCode.BadRequest, buildJsonObject { put("field", field); put("message", message) })

private suspend fun ApplicationCall.notFound() = respondError(HttpStatusCode.NotFound, "not_found")

private fun encodeCursor(key: ItemKey): String = Base64.getUrlEncoder().withoutPadding().encodeToString(key.sk.toByteArray(Charsets.UTF_8))

/** Only the sort key travels in the cursor; the partition key is always rebuilt from the token's `sub`. */
private fun decodeCursor(userId: UserId, cursor: String): ItemKey? {
    if (cursor.length > MAX_CURSOR_LENGTH) return null
    val sk = try {
        String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        return null
    }
    if (TableKeys.parseCallSk(sk) == null) return null
    return ItemKey(Keys.user(userId), sk)
}

/** Finds the User's own call by identifier. Another User's identifier is simply absent here (404). */
private suspend fun findOwn(deps: CallLogRouteDeps, userId: UserId, id: String): CallLogEntry? {
    if (id.isEmpty() || id.length > MAX_CALL_ID_LENGTH) return null
    return try {
        deps.repoFor(userId).findCall(id)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Mounts `GET /calls`, `GET` and `DELETE /calls/{id}`, `GET /calls/{id}/recording`, `POST /calls/{id}/report-status`, `GET /usage/month`. */
fun Route.callLogRoutes(deps: CallLogRouteDeps) {
    get("/calls") {
        val userId = call.userId()
        val limit = when (val raw = call.request.queryParameters["limit"]) {
            null -> DEFAULT_PAGE
            else -> raw.toIntOrNull()?.takeIf { it in 1..UserScopedRepository.MAX_PAGE } ?: return@get call.invalid("limit")
        }
        val after = call.request.queryParameters["cursor"]?.let { decodeCursor(userId, it) ?: return@get call.invalid("cursor") }
        val page = deps.repoFor(userId).listCalls(limit, after)
        call.json(HttpStatusCode.OK, buildJsonObject {
            put("calls", JsonArray(page.entries.map { CoreJson.encodeToJsonElement(CallLogEntry.serializer(), it) }))
            put("nextCursor", page.next?.let { JsonPrimitive(encodeCursor(it)) } ?: JsonNull)
        })
    }
    get("/calls/{id}") {
        val entry = findOwn(deps, call.userId(), call.parameters["id"].orEmpty()) ?: return@get call.notFound()
        call.json(HttpStatusCode.OK, CoreJson.encodeToJsonElement(CallLogEntry.serializer(), entry))
    }
    delete("/calls/{id}") {
        val userId = call.userId()
        val entry = findOwn(deps, userId, call.parameters["id"].orEmpty()) ?: return@delete call.notFound()
        if (!deps.sweeper.deleteCallNow(userId, entry.id)) return@delete call.notFound()
        call.respond(HttpStatusCode.NoContent)
    }
    get("/calls/{id}/recording") {
        val userId = call.userId()
        val entry = findOwn(deps, userId, call.parameters["id"].orEmpty()) ?: return@get call.notFound()
        val url = deps.recordings.presign(userId, entry.id) ?: return@get call.notFound()
        call.json(HttpStatusCode.OK, buildJsonObject {
            put("url", url.url)
            put("expiresInSeconds", url.expiresInSeconds)
        })
    }
    post("/calls/{id}/report-status") {
        val userId = call.userId()
        val body = try {
            Json.parseToJsonElement(call.receiveText()).jsonObject
        } catch (e: SerializationException) {
            return@post call.invalid("body", "malformed JSON")
        } catch (e: IllegalArgumentException) {
            return@post call.invalid("body", "malformed JSON")
        }
        val status = (body["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { s -> ReportStatus.entries.firstOrNull { it.name == s } }
            ?: return@post call.invalid("status", "a report status is required")
        val entry = findOwn(deps, userId, call.parameters["id"].orEmpty()) ?: return@post call.notFound()
        val report = entry.report.copy(status = status, reportedAtEpochMs = deps.clock.nowMillis(), reportedZoneId = entry.zoneId)
        if (!deps.repoFor(userId).updateCallReport(entry.startedAtEpochMs, entry.id, report)) return@post call.notFound()
        call.respond(HttpStatusCode.NoContent)
    }
    get("/usage/month") {
        val userId = call.userId()
        val now = deps.clock.nowMillis()
        val month = MONTH.format(Instant.ofEpochMilli(now).atZone(deps.zone))
        val total = deps.repoFor(userId).getCost(month)?.totalPaise ?: 0L
        val limitInr = deps.settings.get(userId).document.monthlyLimitInr
        call.json(HttpStatusCode.OK, buildJsonObject {
            put("month", month)
            put("totalPaise", total)
            put("monthlyLimitInr", limitInr?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }
}
