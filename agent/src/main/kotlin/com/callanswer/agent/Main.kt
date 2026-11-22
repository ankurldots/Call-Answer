package com.callanswer.agent

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.agentModule(config: Config) {
    routing {
        get("/health") {
            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
        }
    }
}

fun main() {
    val config = Config.fromEnv()
    embeddedServer(Netty, port = config.port) { agentModule(config) }.start(wait = true)
}
