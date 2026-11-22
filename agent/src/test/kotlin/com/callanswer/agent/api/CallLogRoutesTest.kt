package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.Config
import com.callanswer.agent.agentModule
import com.callanswer.agent.store.DirectoryRepository
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.RecordingStore
import com.callanswer.agent.store.RetentionSweeper
import com.callanswer.agent.store.SettingsStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.RecordingInfo
import com.callanswer.core.model.RecordingStatus
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private class LogClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

private fun enc(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

private class LogSigner {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    fun jwks(): String {
        val pub = pair.public as RSAPublicKey
        fun u(v: java.math.BigInteger) = enc(v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        return """{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","use":"sig","n":"${u(pub.modulus)}","e":"${u(pub.publicExponent)}"}]}"""
    }
    fun token(sub: String, nowMs: Long): String {
        val h = enc("""{"alg":"RS256","kid":"k1"}""".toByteArray())
        val c = enc("""{"sub":"$sub","iss":"${JwtVerifier.POOL_ISSUER}","aud":"client-1","token_use":"id","exp":${nowMs / 1000 + 3600}}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update("$h.$c".toByteArray()); sign() }
        return "$h.$c.${enc(sig)}"
    }
}

private fun logEntry(id: String, start: Long) = CallLogEntry(
    id = id, callerNumber = "+919812345678", startedAtEpochMs = start, zoneId = "Asia/Kolkata", durationSec = 12,
    language = Language.EN, intent = IntentCategory.DELIVERY, outcome = Outcome.CALLER_HUNG_UP,
    endReason = EndReason.CALLER_HUNG_UP, transcript = emptyList(), gateStatements = emptyList(),
    voiceGender = VoiceGender.FEMALE, voiceSubstitutions = emptyList(), stallingDurationSec = null,
    takeovers = emptyList(), interruption = null, recording = RecordingInfo(RecordingStatus.PENDING), notes = emptyList(),
)

class CallLogRoutesTest : StringSpec({
    val clock = LogClock()
    val signer = LogSigner()
    val items = InMemoryItemStore()
    val blobs = InMemoryBlobStore()
    val directory = DirectoryRepository(items)
    val repoFor = { u: UserId -> UserScopedRepository(u, items, blobs) }
    val recordings = RecordingStore(blobs) { key, _ -> "https://signed/$key" }
    val settings = SettingsStore(items, directory)
    val deps = CallLogRouteDeps(repoFor, settings, recordings, RetentionSweeper(directory, settings, items, blobs, recordings), clock)

    fun ApplicationTestBuilder.setup() = application {
        agentModule(Config.fromEnv(emptyMap())) {
            companionApi(JwtVerifier(JwtVerifier.POOL_ISSUER, "client-1", { signer.jwks() }, clock), RateLimiter(), profileActiveCheck(repoFor)) {
                callLogRoutes(deps)
            }
        }
    }

    suspend fun ApplicationTestBuilder.call(method: String, path: String, sub: String, body: String? = null): HttpResponse {
        val t = signer.token(sub, clock.now)
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("Authorization", "Bearer $t")
            if (body != null) { header("Content-Type", "application/json"); setBody(body) }
        }
        return when (method) {
            "GET" -> client.get("/api/v1$path", block)
            "POST" -> client.post("/api/v1$path", block)
            else -> client.delete("/api/v1$path", block)
        }
    }

    "list is newest first with a cursor, detail, report status, and usage" {
        val a = UserId("u-a")
        (1..3).forEach { repoFor(a).putCall(logEntry("c$it", 1_000L * it)) }
        repoFor(a).addCost("2023-11", 1234L)
        testApplication {
            setup()
            val p1 = Json.parseToJsonElement(call("GET", "/calls?limit=2", "u-a").bodyAsText()).jsonObject
            p1["calls"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } shouldBe listOf("c3", "c2")
            val cursor = p1["nextCursor"]!!.jsonPrimitive.content
            val p2 = Json.parseToJsonElement(call("GET", "/calls?limit=2&cursor=$cursor", "u-a").bodyAsText()).jsonObject
            p2["calls"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } shouldBe listOf("c1")
            p2["nextCursor"] shouldBe JsonNull
            call("GET", "/calls?limit=0", "u-a").status shouldBe HttpStatusCode.BadRequest
            call("GET", "/calls?cursor=%21%21", "u-a").status shouldBe HttpStatusCode.BadRequest
            call("GET", "/calls/c2", "u-a").status shouldBe HttpStatusCode.OK
            call("POST", "/calls/c2/report-status", "u-a", """{"status":"BOGUS"}""").status shouldBe HttpStatusCode.BadRequest
            call("POST", "/calls/c2/report-status", "u-a", """{"status":"HANDED_TO_MESSAGING_APP"}""").status shouldBe HttpStatusCode.NoContent
            val d = Json.parseToJsonElement(call("GET", "/calls/c2", "u-a").bodyAsText()).jsonObject
            d["report"]!!.jsonObject["status"]!!.jsonPrimitive.content shouldBe "HANDED_TO_MESSAGING_APP"
            d["report"]!!.jsonObject["reportedAtEpochMs"]!!.jsonPrimitive.long shouldBe clock.now
            val usage = Json.parseToJsonElement(call("GET", "/usage/month", "u-a").bodyAsText()).jsonObject
            usage["month"]!!.jsonPrimitive.content shouldBe "2023-11"
            usage["totalPaise"]!!.jsonPrimitive.long shouldBe 1234L
        }
    }

    "recording presign, delete, and another User's call identifier answers 404" {
        val b = UserId("u-b")
        repoFor(b).putCall(logEntry("cb", 5_000L))
        recordings.upload(b, "cb", byteArrayOf(1, 2, 3))
        testApplication {
            setup()
            for ((m, p) in listOf("GET" to "/calls/cb", "DELETE" to "/calls/cb", "GET" to "/calls/cb/recording", "POST" to "/calls/cb/report-status")) {
                call(m, p, "u-other", """{"status":"SENT"}""").status shouldBe HttpStatusCode.NotFound
            }
            val rec = Json.parseToJsonElement(call("GET", "/calls/cb/recording", "u-b").bodyAsText()).jsonObject
            rec["url"]!!.jsonPrimitive.content shouldBe "https://signed/users/u-b/recordings/cb.wav"
            rec["expiresInSeconds"]!!.jsonPrimitive.long shouldBe 600L
            call("DELETE", "/calls/cb", "u-b").status shouldBe HttpStatusCode.NoContent
            call("GET", "/calls/cb", "u-b").status shouldBe HttpStatusCode.NotFound
            call("GET", "/calls/cb/recording", "u-b").status shouldBe HttpStatusCode.NotFound
            blobs.listKeys("users/u-b/") shouldBe emptyList()
        }
    }
})
