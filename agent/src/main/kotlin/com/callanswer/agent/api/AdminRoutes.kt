package com.callanswer.agent.api

import com.callanswer.agent.store.AssignResult
import com.callanswer.agent.store.DirectoryRepository
import com.callanswer.agent.store.NumberAssignmentService
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.PhoneNumbers
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The stores behind the Admin_API routes. */
class AdminRouteDeps(
    val assignments: NumberAssignmentService,
    val directory: DirectoryRepository,
    val repoFor: (UserId) -> UserScopedRepository,
)

private suspend fun ApplicationCall.respondAdminJson(status: HttpStatusCode, body: JsonElement) =
    respondText(body.toString(), ContentType.Application.Json, status)

/**
 * Mounts the Admin_API under `/admin`. Every route answers 403 unless the token's `cognito:groups` contains `admins`.
 * An assignment conflict (the number or the User already assigned) answers 409.
 */
fun Route.adminRoutes(deps: AdminRouteDeps) = route("/admin") {
    get("/users") {
        if (!call.requireAdmin()) return@get
        val users = buildJsonArray {
            for (u in deps.directory.listUsers()) {
                val status = deps.repoFor(u).getProfile()?.status ?: "ACTIVE"
                val number = deps.directory.numberOf(u)
                add(buildJsonObject {
                    put("userId", u.sub)
                    put("status", status)
                    put("virtualNumber", number?.value?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
        }
        call.respondAdminJson(HttpStatusCode.OK, buildJsonObject { put("users", users) })
    }

    get("/numbers") {
        if (!call.requireAdmin()) return@get
        val numbers = JsonArray(deps.assignments.list().map {
            buildJsonObject {
                put("e164", it.e164.value)
                put("userId", it.userId.sub)
                put("assignedAt", it.assignedAtMs)
                put("assignedBy", it.assignedBy)
            }
        })
        call.respondAdminJson(HttpStatusCode.OK, buildJsonObject { put("numbers", numbers) })
    }

    put("/numbers/{e164}") {
        if (!call.requireAdmin()) return@put
        val raw = call.parameters["e164"].orEmpty()
        val e164 = PhoneNumbers.normalize(raw) ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_number")
        val userSub = try {
            (Json.parseToJsonElement(call.receiveText()).jsonObject["userId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        val userId = userSub?.let { runCatching { UserId(it) }.getOrNull() }
            ?: return@put call.respondError(HttpStatusCode.BadRequest, "invalid_user_id")
        when (deps.assignments.assign(e164.value, userId, call.caller.userId)) {
            is AssignResult.Assigned -> call.respondAdminJson(HttpStatusCode.OK, buildJsonObject {
                put("e164", e164.value); put("userId", userId.sub)
            })
            AssignResult.InvalidNumber -> call.respondError(HttpStatusCode.BadRequest, "invalid_number")
            AssignResult.NumberIsUserMobile -> call.respondError(HttpStatusCode.BadRequest, "number_is_user_mobile")
            AssignResult.Conflict -> call.respondError(HttpStatusCode.Conflict, "assignment_conflict")
        }
    }

    delete("/numbers/{e164}") {
        if (!call.requireAdmin()) return@delete
        val e164 = PhoneNumbers.normalize(call.parameters["e164"].orEmpty())
            ?: return@delete call.respondError(HttpStatusCode.BadRequest, "invalid_number")
        val owner = deps.directory.lookupNumber(e164)
        if (owner == null || !deps.assignments.unassign(owner.userId)) {
            return@delete call.respondError(HttpStatusCode.NotFound, "not_assigned")
        }
        call.respond(HttpStatusCode.NoContent)
    }

    post("/users/{userId}/deactivate") {
        if (!call.requireAdmin()) return@post
        val userId = runCatching { UserId(call.parameters["userId"].orEmpty()) }.getOrNull()
            ?: return@post call.respondError(HttpStatusCode.BadRequest, "invalid_user_id")
        if (!deps.assignments.deactivate(userId)) return@post call.respondError(HttpStatusCode.NotFound, "user_not_found")
        call.respond(HttpStatusCode.NoContent)
    }
}
