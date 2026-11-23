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
import com.callanswer.agent.call.CallFinalizer
import com.callanswer.agent.call.SonicStreamCallFactory
import com.callanswer.agent.exotel.ExotelAuth
import com.callanswer.agent.exotel.ExotelRouteDeps
import com.callanswer.agent.exotel.ExotelStreamHandler
import com.callanswer.agent.exotel.SecretsManagerSource
import com.callanswer.agent.exotel.exotelResolveRoute
import com.callanswer.agent.exotel.exotelStreamRoute
import com.callanswer.agent.sonic.RenewalConfig
import com.callanswer.agent.sonic.SonicClient
import com.callanswer.agent.sonic.SonicStreamFactory
import com.callanswer.agent.sonic.SonicStreamListener
import com.callanswer.agent.store.CostCounter
import com.callanswer.agent.store.CostNotifier
import com.callanswer.agent.store.TenantResolver
import com.callanswer.agent.store.UnassignedCallLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import com.callanswer.agent.store.AwsStores
import com.callanswer.agent.store.DirectoryRepository
import com.callanswer.agent.store.SettingsStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.UserId
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.websocket.WebSockets
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.agentModule(
    config: Config,
    companion: (Route.() -> Unit)? = null,
    exotel: (Route.() -> Unit)? = null,
) {
    install(WebSockets)
    routing {
        companion?.invoke(this)
        exotel?.invoke(this)
        get("/health") {
            call.respondText("""{"status":"UP"}""", ContentType.Application.Json)
        }
    }
}

fun main() {
    val config = Config.fromEnv()
    val items = AwsStores.itemStore(config.region, config.tableName)
    val blobs = AwsStores.blobStore(config.region, config.recordingsBucket)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sonic by lazy { SonicClient(SonicClient.buildBedrockClient(), scope, config.sonicModelId) }
    embeddedServer(Netty, port = config.port) { agentModule(config, companion = {
        val verifier = JwtVerifier(
            issuer = JwtVerifier.POOL_ISSUER,
            clientId = System.getenv("COGNITO_CLIENT_ID") ?: "",
            jwksSource = JwtVerifier.downloadJwks(),
        )
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
    }, exotel = {
        val auth = ExotelAuth(SecretsManagerSource(SecretsManagerClient.builder().region(Region.of(config.region)).build()), config.exotelSecretName, config.callTokenSecretName)
        val resolver = TenantResolver(items, UnassignedCallLog(items))
        val directory = DirectoryRepository(items)
        val recordings = AwsStores.recordingStore(config.region, config.recordingsBucket, blobs)
        val noNotify = object : CostNotifier {
            override suspend fun limit80Reached(totalPaise: Long) {}
            override suspend fun limit100Reached(totalPaise: Long) {}
        }
        val calls = SonicStreamCallFactory(
            settings = SettingsStore(items, directory),
            repoFor = { u -> UserScopedRepository(u, items, blobs) },
            finalizerFor = { u, repo -> CallFinalizer(u, repo, CostCounter(repo, noNotify), recordings) },
            sonic = object : SonicStreamFactory {
                override fun open(listener: SonicStreamListener) = sonic.open(listener)
            },
            scope = scope,
            renewal = RenewalConfig(maxSessionSeconds = config.sonicMaxSessionSeconds, idleSeconds = config.sonicIdleSeconds),
        )
        exotelResolveRoute(ExotelRouteDeps(auth, resolver, config.exotelStreamHost, config.exotelSampleRate))
        exotelStreamRoute(
            auth,
            ExotelStreamHandler(
                resolver, calls, SystemClock, config.exotelChunkBytes, config.exotelSendAheadMs,
                config.exotelMediaTimeoutSeconds * 1000L,
            ),
        )
    }) }.start(wait = true)
}
