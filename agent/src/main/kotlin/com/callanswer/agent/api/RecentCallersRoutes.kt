package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.UserId
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MAX_MINUTES = 60
private const val MS_PER_MINUTE = 60_000L

/**
 * Mounts `GET /recent-callers?minutes=n` (n from 1 to 60, default 60): the User's own Recent_Call_Records
 * whose `endMs` is within the last n minutes, newest first. The User comes from the verified token only.
 */
fun Route.recentCallersRoutes(repoFor: (UserId) -> UserScopedRepository, clock: Clock) {
    get("/recent-callers") {
        val minutes = when (val raw = call.request.queryParameters["minutes"]) {
            null -> MAX_MINUTES
            else -> raw.toIntOrNull()?.takeIf { it in 1..MAX_MINUTES } ?: return@get call.respondText(
                buildJsonObject { put("field", "minutes"); put("message", "invalid value") }.toString(),
                ContentType.Application.Json, HttpStatusCode.BadRequest,
            )
        }
        val since = clock.nowMillis() - minutes * MS_PER_MINUTE
        val callers = repoFor(UserId(call.caller.userId)).listRecentCallers().filter { it.second.endMs >= since }
        call.respondText(
            buildJsonObject {
                put("callers", JsonArray(callers.map { (number, rec) ->
                    buildJsonObject {
                        put("number", number.value)
                        put("endMs", rec.endMs)
                        put("intent", rec.intent.name)
                    }
                }))
            }.toString(),
            ContentType.Application.Json, HttpStatusCode.OK,
        )
    }
}
