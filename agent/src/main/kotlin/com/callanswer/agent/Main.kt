package com.callanswer.agent

import com.callanswer.agent.api.COMPANION_API_HOST
import com.callanswer.agent.api.JwtVerifier
import com.callanswer.agent.api.RateLimiter
import com.callanswer.agent.api.companionApi
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.agentModule(config: Config, companion: (Route.() -> Unit)? = null) {
    routing {
        companion?.invoke(this)
        get("/health") {
            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
        }
    }
}

fun main() {
    val config = Config.fromEnv()
    embeddedServer(Netty, port = config.port) { agentModule(config, companion = {
        val verifier = JwtVerifier(
            issuer = JwtVerifier.POOL_ISSUER,
            clientId = System.getenv("COGNITO_CLIENT_ID") ?: "",
            jwksSource = JwtVerifier.downloadJwks(),
        )
        // isUserActive is replaced by the profile lookup when the stores exist (tasks 10.2/10.3).
        companionApi(verifier, RateLimiter(), isUserActive = { true }, host = COMPANION_API_HOST) {}
    }) }.start(wait = true)
}
