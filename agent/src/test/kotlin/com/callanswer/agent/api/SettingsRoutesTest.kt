package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.Config
import com.callanswer.agent.agentModule
import com.callanswer.agent.store.DirectoryRepository
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.SettingsStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private class FixedClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

private class Signer {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    fun jwks(): String {
        val pub = pair.public as RSAPublicKey
        fun u(v: java.math.BigInteger) = b64(v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        return """{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","use":"sig","n":"${u(pub.modulus)}","e":"${u(pub.publicExponent)}"}]}"""
    }
    fun token(sub: String, nowMs: Long): String {
        val h = b64("""{"alg":"RS256","kid":"k1"}""".toByteArray())
        val c = b64("""{"sub":"$sub","iss":"${JwtVerifier.POOL_ISSUER}","aud":"client-1","token_use":"id","exp":${nowMs / 1000 + 3600}}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update("$h.$c".toByteArray()); sign() }
        return "$h.$c.${b64(sig)}"
    }
}

private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

class SettingsRoutesTest : StringSpec({
    val clock = FixedClock()
    val signer = Signer()
    val items = InMemoryItemStore()
    val blobs = InMemoryBlobStore()
    val directory = DirectoryRepository(items)
    val repoFor = { u: UserId -> UserScopedRepository(u, items, blobs) }
    val deps = SettingsRouteDeps(repoFor, SettingsStore(items, directory), directory, clock)

    fun ApplicationTestBuilder.setup() = application {
        agentModule(Config.fromEnv(emptyMap())) {
            companionApi(JwtVerifier(JwtVerifier.POOL_ISSUER, "client-1", { signer.jwks() }, clock), RateLimiter(), profileActiveCheck(repoFor)) {
                settingsRoutes(deps)
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
            "PUT" -> client.put("/api/v1$path", block)
            "POST" -> client.post("/api/v1$path", block)
            else -> client.delete("/api/v1$path", block)
        }
    }

    "me creates the profile, shows the number and consent; settings flow with 200, 409, 400" {
        directory.assign(E164("+918000000001"), UserId("u-a"), 1L, "test")
        testApplication {
            setup()
            val me = obj(call("GET", "/me", "u-a").bodyAsText())
            me["userId"]!!.jsonPrimitive.content shouldBe "u-a"
            me["status"]!!.jsonPrimitive.content shouldBe "ACTIVE"
            me["virtualNumber"]!!.jsonPrimitive.content shouldBe "+918000000001"
            me["consentAccepted"]!!.jsonPrimitive.boolean shouldBe false
            me["settingsVersion"]!!.jsonPrimitive.long shouldBe 0L
            obj(call("GET", "/me", "u-b").bodyAsText())["virtualNumber"] shouldBe JsonNull

            obj(call("GET", "/settings", "u-a").bodyAsText())["version"]!!.jsonPrimitive.long shouldBe 0L
            val ok = call("PUT", "/settings", "u-a", """{"expectedVersion":0,"settings":{"agentEnabled":true,"userMobile":"+919876543210"}}""")
            ok.status shouldBe HttpStatusCode.OK
            obj(ok.bodyAsText())["version"]!!.jsonPrimitive.long shouldBe 1L
            val conflict = call("PUT", "/settings", "u-a", """{"expectedVersion":0,"settings":{"agentEnabled":false}}""")
            conflict.status shouldBe HttpStatusCode.Conflict
            val cb = obj(conflict.bodyAsText())
            cb["currentVersion"]!!.jsonPrimitive.long shouldBe 1L
            cb["settings"]!!.jsonObject["agentEnabled"]!!.jsonPrimitive.boolean shouldBe true
            val bad = call("PUT", "/settings", "u-a", """{"expectedVersion":1,"settings":{"userMobile":"+918000000001"}}""")
            bad.status shouldBe HttpStatusCode.BadRequest
            obj(bad.bodyAsText())["field"]!!.jsonPrimitive.content shouldBe "userMobile"
            call("PUT", "/settings", "u-a", "not json").status shouldBe HttpStatusCode.BadRequest
            // another User never sees these settings
            obj(call("GET", "/settings", "u-b").bodyAsText())["version"]!!.jsonPrimitive.long shouldBe 0L
        }
    }

    "consent accept and withdraw turns the agent off; deactivated User gets 403" {
        testApplication {
            setup()
            call("GET", "/me", "u-c")
            call("POST", "/consent", "u-c", "{}").status shouldBe HttpStatusCode.BadRequest
            call("POST", "/consent", "u-c", """{"version":"v1"}""").status shouldBe HttpStatusCode.NoContent
            obj(call("GET", "/me", "u-c").bodyAsText())["consentAccepted"]!!.jsonPrimitive.boolean shouldBe true
            call("PUT", "/settings", "u-c", """{"expectedVersion":0,"settings":{"agentEnabled":true}}""").status shouldBe HttpStatusCode.OK
            call("DELETE", "/consent", "u-c").status shouldBe HttpStatusCode.NoContent
            val me = obj(call("GET", "/me", "u-c").bodyAsText())
            me["consentAccepted"]!!.jsonPrimitive.boolean shouldBe false
            me["settingsVersion"]!!.jsonPrimitive.long shouldBe 2L
            obj(call("GET", "/settings", "u-c").bodyAsText())["settings"]!!.jsonObject["agentEnabled"]!!.jsonPrimitive.boolean shouldBe false

            repoFor(UserId("u-c")).setProfileStatus("DEACTIVATED")
            call("GET", "/me", "u-c").status shouldBe HttpStatusCode.Forbidden
        }
    }

    "devices: keep 5, replace the oldest, delete, validate" {
        testApplication {
            setup()
            for (i in 1..6) {
                clock.now += 1000
                call("PUT", "/devices/d$i", "u-d", """{"fcmToken":"tok$i"}""").status shouldBe HttpStatusCode.NoContent
            }
            repoFor(UserId("u-d")).listDevices().map { it.deviceId }.toSet() shouldBe setOf("d2", "d3", "d4", "d5", "d6")
            call("DELETE", "/devices/d3", "u-d").status shouldBe HttpStatusCode.NoContent
            repoFor(UserId("u-d")).listDevices().size shouldBe 4
            call("PUT", "/devices/d9", "u-d", "{}").status shouldBe HttpStatusCode.BadRequest
            call("PUT", "/devices/a%23b", "u-d", """{"fcmToken":"x"}""").status shouldBe HttpStatusCode.BadRequest
            repoFor(UserId("u-e")).listDevices().size shouldBe 0
        }
    }
})
