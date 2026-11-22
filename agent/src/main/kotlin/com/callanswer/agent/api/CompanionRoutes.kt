package com.callanswer.agent.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.intercept
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey

const val COMPANION_API_HOST = "api.leadingdots.com"
const val COMPANION_BASE_PATH = "/api/v1"
const val ADMIN_GROUP = "admins"

/** The verified caller. [userId] comes only from the token's `sub`. */
data class Caller(val userId: String, val groups: Set<String>) {
    val isAdmin: Boolean get() = ADMIN_GROUP in groups
}

private val CallerKey = AttributeKey<Caller>("companion.caller")

/** The verified caller of this request. Handlers must take the User_Id from here, never from path, query, or body. */
val ApplicationCall.caller: Caller get() = attributes[CallerKey]

/** Answers 403 and returns false unless the caller is in the `admins` group. */
suspend fun ApplicationCall.requireAdmin(): Boolean {
    if (caller.isAdmin) return true
    respondError(HttpStatusCode.Forbidden, "forbidden")
    return false
}

suspend fun ApplicationCall.respondError(status: HttpStatusCode, error: String) =
    respondText("""{"error":"$error"}""", ContentType.Application.Json, status)

/**
 * Mounts the Companion_API under [COMPANION_BASE_PATH]. Every route inside [build] runs after:
 * host check (when [host] is set), bearer token verification (401), rate limit per `sub` (429),
 * and the deactivated-User check (403, [isUserActive]).
 */
fun Route.companionApi(
    verifier: JwtVerifier,
    limiter: RateLimiter,
    isUserActive: suspend (userId: String) -> Boolean,
    host: String? = null,
    build: Route.() -> Unit,
): Route = route(COMPANION_BASE_PATH) {
    intercept(ApplicationCallPipeline.Plugins) {
        val call = call
        if (host != null && call.request.header(HttpHeaders.Host)?.substringBefore(':')?.lowercase() != host) {
            call.respondError(HttpStatusCode.NotFound, "not_found")
            finish()
            return@intercept
        }
        val bearer = call.request.header(HttpHeaders.Authorization)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
        val verified = try {
            verifier.verify(bearer ?: throw JwtException("missing bearer token"))
        } catch (e: JwtException) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized")
            finish()
            return@intercept
        }
        if (!limiter.tryAcquire(verified.sub)) {
            call.response.header(HttpHeaders.RetryAfter, "60")
            call.respondError(HttpStatusCode.TooManyRequests, "rate_limited")
            finish()
            return@intercept
        }
        if (!isUserActive(verified.sub)) {
            call.respondError(HttpStatusCode.Forbidden, "user_deactivated")
            finish()
            return@intercept
        }
        call.attributes.put(CallerKey, Caller(verified.sub, verified.groups))
    }
    build()
}
