package com.callanswer.agent

import com.callanswer.agent.api.COMPANION_API_HOST
import com.callanswer.agent.api.JwtVerifier
import com.callanswer.agent.api.RateLimiter
import com.callanswer.agent.api.SettingsRouteDeps
import com.callanswer.agent.api.adminRoutes
import com.callanswer.agent.api.CallLogRouteDeps
import com.callanswer.agent.api.callLogRoutes
import com.callanswer.agent.api.companionApi
import com.callanswer.agent.api.profileActiveCheck
import com.callanswer.agent.api.recentCallersRoutes
import com.callanswer.agent.api.settingsRoutes
import com.callanswer.agent.store.AwsStores
import com.callanswer.agent.store.DirectoryRepository
import com.callanswer.agent.store.SettingsStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.UserId
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
        val items = AwsStores.itemStore(config.region, config.tableName)
        val blobs = AwsStores.blobStore(config.region, config.recordingsBucket)
        val directory = DirectoryRepository(items)
        val repoFor = { u: UserId -> UserScopedRepository(u, items, blobs) }
        val deps = SettingsRouteDeps(repoFor, SettingsStore(items, directory), directory, SystemClock)
        companionApi(verifier, RateLimiter(), isUserActive = profileActiveCheck(repoFor), host = COMPANION_API_HOST) {
            settingsRoutes(deps)
            val settingsStore = SettingsStore(items, directory)
            val recordings = AwsStores.recordingStore(config.region, config.recordingsBucket, blobs)
            val sweeper = com.callanswer.agent.store.RetentionSweeper(directory, settingsStore, items, blobs, recordings)
            callLogRoutes(CallLogRouteDeps(repoFor, settingsStore, recordings, sweeper, SystemClock))
            recentCallersRoutes(repoFor, SystemClock)
            adminRoutes(com.callanswer.agent.api.AdminRouteDeps(com.callanswer.agent.store.NumberAssignmentService(items), directory, repoFor))
        }
    }) }.start(wait = true)
}
