package com.callanswer.agent.exotel

import com.callanswer.agent.store.TenantResolution
import com.callanswer.agent.store.TenantResolver
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.intercept
import io.ktor.server.routing.route
import io.ktor.server.websocket.webSocket
import io.ktor.util.AttributeKey
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the Exotel HTTP routes need. [streamHost] and [sampleRate] build the returned Voice_Stream URL. */
class ExotelRouteDeps(
    val auth: ExotelAuth,
    val resolver: TenantResolver,
    val streamHost: String,
    val sampleRate: Int,
)

/**
 * `GET|POST /exotel/resolve`: the dynamic URL of the Voicebot applet. The tenant is chosen only from the
 * called number (`CallTo`, fallback `To`); `From`, `CallFrom` and `ForwardedFrom` are never read.
 * Tokens and credentials are never logged.
 */
fun Route.exotelResolveRoute(deps: ExotelRouteDeps) {
    get("/exotel/resolve") { resolve(call, deps) }
    post("/exotel/resolve") { resolve(call, deps) }
}

private val CLAIMS_KEY = AttributeKey<CallTokenClaims>("exotel.callTokenClaims")

/**
 * `/stream/{token}`: the media WebSocket. Before the upgrade, Basic auth and the call token (MAC, expiry) are
 * checked and a failure answers 401, so a refused handshake never reaches [handler]. Tokens are never logged.
 */
fun Route.exotelStreamRoute(auth: ExotelAuth, handler: ExotelStreamHandler) {
    route("/stream/{token}") {
        intercept(ApplicationCallPipeline.Plugins) {
            val claims = if (auth.checkBasic(call.request.header(HttpHeaders.Authorization))) {
                call.parameters["token"]?.let { auth.inspectCallToken(it) }
            } else {
                null
            }
            if (claims == null) {
                call.respond(HttpStatusCode.Unauthorized)
                finish()
            } else {
                call.attributes.put(CLAIMS_KEY, claims)
            }
        }
        webSocket {
            handler.run(this, call.attributes[CLAIMS_KEY])
        }
    }
}

private suspend fun resolve(call: ApplicationCall, deps: ExotelRouteDeps) {
    if (!deps.auth.checkBasic(call.request.header(HttpHeaders.Authorization))) {
        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"exotel\"")
        call.respond(HttpStatusCode.Unauthorized)
        return
    }
    val form = if (call.request.httpMethod == HttpMethod.Post) {
        runCatching { call.receiveParameters() }.getOrNull()
    } else {
        null
    }
    fun param(name: String): String? =
        (call.request.queryParameters[name] ?: form?.get(name))?.takeIf { it.isNotBlank() }

    val callSid = param("CallSid")
    if (callSid == null) {
        call.respondJson(HttpStatusCode.BadRequest, """{"error":"MISSING_CALL_SID"}""")
        return
    }
    when (val resolved = deps.resolver.resolve(param("CallTo") ?: param("To"))) {
        is TenantResolution.Resolved -> {
            val token = deps.auth.createCallToken(callSid, resolved.userId.sub)
            val url = "wss://${deps.streamHost}/stream/$token?sample-rate=${deps.sampleRate}"
            call.respondJson(HttpStatusCode.OK, buildJsonObject { put("url", url) }.toString())
        }
        is TenantResolution.OutOfService -> call.respondJson(HttpStatusCode.NotFound, """{"error":"OUT_OF_SERVICE"}""")
    }
}

private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: String) =
    respondText(body, ContentType.Application.Json, status)
