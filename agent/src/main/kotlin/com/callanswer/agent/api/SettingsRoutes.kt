package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.store.ConsentRecord
import com.callanswer.agent.store.DeviceRecord
import com.callanswer.agent.store.DirectoryRepository
import com.callanswer.agent.store.SettingsStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.Settings
import com.callanswer.core.tenant.SettingsUpdate
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Answer time limit of `PUT /settings` (Requirement 20 criterion 2). */
const val SETTINGS_TIMEOUT_MS: Long = 3_000

private const val MAX_ID_LENGTH = 128
private const val MAX_TOKEN_LENGTH = 4096
private const val MAX_WITHDRAW_ATTEMPTS = 5

/** The stores behind the settings, consent, and device routes. */
class SettingsRouteDeps(
    val repoFor: (UserId) -> UserScopedRepository,
    val settings: SettingsStore,
    val directory: DirectoryRepository,
    val clock: Clock,
)

/**
 * The profile lookup for the deactivated-User check of [companionApi]. A User without a profile is active (the
 * profile is created by the first `GET /me`). A `sub` that cannot be a [UserId] is refused.
 */
fun profileActiveCheck(repoFor: (UserId) -> UserScopedRepository): suspend (String) -> Boolean = { sub ->
    val userId = runCatching { UserId(sub) }.getOrNull()
    if (userId == null) false else repoFor(userId).getProfile()?.status != "DEACTIVATED"
}

private fun ApplicationCall.userId(): UserId = UserId(caller.userId)

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonElement) =
    respondText(body.toString(), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondInvalid(field: String, message: String = "invalid value") =
    respondJson(HttpStatusCode.BadRequest, buildJsonObject { put("field", field); put("message", message) })

private fun settingsDocument(version: Long, settings: Settings): JsonObject = buildJsonObject {
    put("version", version)
    put("settings", CoreJson.encodeToJsonElement(Settings.serializer(), settings.copy(version = version)))
}

private suspend fun ApplicationCall.bodyObject(): JsonObject? = try {
    Json.parseToJsonElement(receiveText()).jsonObject
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/** Mounts `GET /me`, `GET` and `PUT /settings`, `POST` and `DELETE /consent`, and `PUT` and `DELETE /devices/{deviceId}`. */
fun Route.settingsRoutes(deps: SettingsRouteDeps) {
    get("/me") {
        val userId = call.userId()
        val repo = deps.repoFor(userId)
        repo.createProfileIfAbsent(deps.clock.nowMillis())
        val profile = repo.getProfile()
        val consent = repo.getConsent()
        val number = deps.directory.numberOf(userId)
        val version = deps.settings.get(userId).version
        call.respondJson(HttpStatusCode.OK, buildJsonObject {
            put("userId", userId.sub)
            put("status", profile?.status ?: "ACTIVE")
            put("virtualNumber", number?.value?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
            put("consentAccepted", consent != null && consent.withdrawnAtMs == null)
            put("settingsVersion", version)
        })
    }

    get("/settings") {
        val saved = deps.settings.get(call.userId())
        call.respondJson(HttpStatusCode.OK, settingsDocument(saved.version, saved.document))
    }

    put("/settings") {
        val userId = call.userId()
        val body = call.bodyObject() ?: return@put call.respondInvalid("body", "malformed JSON")
        val expected = (body["expectedVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()
            ?: return@put call.respondInvalid("expectedVersion", "a number is required")
        val candidate = try {
            CoreJson.decodeFromJsonElement(Settings.serializer(), body["settings"] as? JsonObject ?: return@put call.respondInvalid("settings", "an object is required"))
        } catch (e: SerializationException) {
            return@put call.respondInvalid("settings")
        } catch (e: IllegalArgumentException) {
            return@put call.respondInvalid("settings")
        }
        val result = try {
            withTimeout(SETTINGS_TIMEOUT_MS) { deps.settings.update(userId, expected, candidate) }
        } catch (e: TimeoutCancellationException) {
            return@put call.respondError(HttpStatusCode.ServiceUnavailable, "timeout")
        }
        when (result) {
            is SettingsUpdate.Saved -> call.respondJson(HttpStatusCode.OK, settingsDocument(result.version, result.document))
            is SettingsUpdate.Invalid -> call.respondInvalid(result.field)
            is SettingsUpdate.Conflict -> call.respondJson(HttpStatusCode.Conflict, buildJsonObject {
                put("currentVersion", result.currentVersion)
                put("settings", CoreJson.encodeToJsonElement(Settings.serializer(), result.currentDocument.copy(version = result.currentVersion)))
            })
        }
    }

    post("/consent") {
        val body = call.bodyObject() ?: return@post call.respondInvalid("body", "malformed JSON")
        val version = (body["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (version.isNullOrEmpty() || version.length > MAX_ID_LENGTH) return@post call.respondInvalid("version", "the consent text version is required")
        deps.repoFor(call.userId()).putConsent(ConsentRecord(version, deps.clock.nowMillis()))
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/consent") {
        val userId = call.userId()
        val repo = deps.repoFor(userId)
        val consent = repo.getConsent()
        if (consent != null && consent.withdrawnAtMs == null) {
            repo.putConsent(consent.copy(withdrawnAtMs = deps.clock.nowMillis()))
        }
        // Withdrawal turns the Agent off (Requirement 17 criterion 4); retry when a concurrent edit moves the version.
        for (attempt in 1..MAX_WITHDRAW_ATTEMPTS) {
            val current = deps.settings.get(userId)
            if (!current.document.agentEnabled ||
                deps.settings.update(userId, current.version, current.document.copy(agentEnabled = false)) is SettingsUpdate.Saved
            ) {
                return@delete call.respond(HttpStatusCode.NoContent)
            }
        }
        call.respondError(HttpStatusCode.Conflict, "settings_busy")
    }

    put("/devices/{deviceId}") {
        val deviceId = call.parameters["deviceId"].orEmpty()
        if (!validDeviceId(deviceId)) return@put call.respondInvalid("deviceId")
        val body = call.bodyObject() ?: return@put call.respondInvalid("body", "malformed JSON")
        val token = (body["fcmToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        if (token.isNullOrEmpty() || token.length > MAX_TOKEN_LENGTH) return@put call.respondInvalid("fcmToken", "a token is required")
        deps.repoFor(call.userId()).putDevice(DeviceRecord(deviceId, token, deps.clock.nowMillis()))
        call.respond(HttpStatusCode.NoContent)
    }

    delete("/devices/{deviceId}") {
        val deviceId = call.parameters["deviceId"].orEmpty()
        if (!validDeviceId(deviceId)) return@delete call.respondInvalid("deviceId")
        deps.repoFor(call.userId()).deleteDevice(deviceId)
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun validDeviceId(id: String): Boolean =
    id.isNotEmpty() && id.length <= MAX_ID_LENGTH && id.none { it == '/' || it == '\\' || it == '#' || it.isWhitespace() } && ".." !in id
