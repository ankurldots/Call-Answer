package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.Config
import com.callanswer.agent.agentModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private const val ISS = JwtVerifier.POOL_ISSUER
private const val CLIENT = "client-1"

private class ManualClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

private val ADMIN_X = ",\"cognito:groups\":[\"admins\",\"x\"]"
private val ADMIN_ONLY = ",\"cognito:groups\":[\"admins\"]"

private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

private class TestKey(val kid: String) {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    fun jwk(): String {
        val pub = pair.public as RSAPublicKey
        fun u(v: java.math.BigInteger) = b64(v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        return """{"kty":"RSA","kid":"$kid","alg":"RS256","use":"sig","n":"${u(pub.modulus)}","e":"${u(pub.publicExponent)}"}"""
    }
    fun token(claims: String, alg: String = "RS256"): String {
        val h = b64("""{"alg":"$alg","kid":"$kid"}""".toByteArray())
        val c = b64(claims.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private); update("$h.$c".toByteArray()); sign()
        }
        return "$h.$c.${b64(sig)}"
    }
}

private fun claims(
    clock: ManualClock, sub: String = "user-1", iss: String = ISS, aud: String = CLIENT,
    use: String = "id", expOffset: Long = 3600, groups: String = "",
) = """{"sub":"$sub","iss":"$iss","aud":"$aud","token_use":"$use","exp":${clock.now / 1000 + expOffset}$groups}"""

class CompanionApiTest : StringSpec({
    val clock = ManualClock()
    val key = TestKey("k1")
    val other = TestKey("k1")
    val jwks = """{"keys":[${key.jwk()}]}"""
    fun verifier(src: () -> String = { jwks }) = JwtVerifier(ISS, CLIENT, src, clock)

    "valid token returns sub and groups" {
        val t = key.token(claims(clock, groups = ADMIN_X))
        val v = verifier().verify(t)
        v.sub shouldBe "user-1"
        v.groups shouldBe setOf("admins", "x")
    }

    "invalid tokens are rejected" {
        val bad = listOf(
            key.token(claims(clock, expOffset = -1)),
            key.token(claims(clock, iss = "https://evil")),
            key.token(claims(clock, aud = "other")),
            key.token(claims(clock, use = "refresh")),
            other.token(claims(clock)),
            key.token(claims(clock), alg = "none"),
            "garbage",
        )
        for (t in bad) {
            (runCatching { verifier().verify(t) }.exceptionOrNull() is JwtException) shouldBe true
        }
    }

    "access token needs client_id" {
        val ok = key.token("""{"sub":"u","iss":"$ISS","client_id":"$CLIENT","token_use":"access","exp":${clock.now / 1000 + 60}}""")
        verifier().verify(ok).sub shouldBe "u"
    }

    "JWKS is cached for one hour" {
        var fetches = 0
        val v = verifier { fetches++; jwks }
        val t = key.token(claims(clock, expOffset = 100_000))
        v.verify(t); v.verify(t)
        fetches shouldBe 1
        clock.now += 3_600_000
        v.verify(t)
        fetches shouldBe 2
        clock.now -= 3_600_000
    }

    "rate limiter allows 60 per minute per key" {
        val c = ManualClock(0)
        val r = RateLimiter(clock = c)
        (1..60).all { r.tryAcquire("a") } shouldBe true
        r.tryAcquire("a") shouldBe false
        r.tryAcquire("b") shouldBe true
        c.now += 60_000
        r.tryAcquire("a") shouldBe true
    }

    "routes: 401, 200 with sub, 403 deactivated, 403 non-admin, 429" {
        val active = mutableMapOf("user-1" to true, "user-2" to false)
        testApplication {
            application {
                agentModule(Config.fromEnv(emptyMap())) {
                    companionApi(verifier(), RateLimiter(limit = 3, clock = clock), { active[it] == true }) {
                        get("/whoami/{userId}") { call.respondText(call.caller.userId) }
                        get("/admin/x") { if (call.requireAdmin()) call.respondText("ok") }
                    }
                }
            }
            val t1 = key.token(claims(clock))
            suspend fun get(path: String, token: String?) = client.get("/api/v1$path") {
                token?.let { header("Authorization", "Bearer $it") }
            }
            get("/whoami/x", null).status shouldBe HttpStatusCode.Unauthorized
            get("/whoami/x", "bad").status shouldBe HttpStatusCode.Unauthorized
            get("/whoami/other?userId=evil", t1).bodyAsText() shouldBe "user-1"
            get("/admin/x", t1).status shouldBe HttpStatusCode.Forbidden
            get("/admin/x", key.token(claims(clock, groups = ADMIN_ONLY))).status shouldBe HttpStatusCode.OK
            get("/whoami/x", t1).status shouldBe HttpStatusCode.TooManyRequests
            get("/whoami/x", key.token(claims(clock, sub = "user-2"))).status shouldBe HttpStatusCode.Forbidden
        }
    }

    "host restriction" {
        testApplication {
            application {
                agentModule(Config.fromEnv(emptyMap())) {
                    companionApi(verifier(), RateLimiter(), { true }, host = COMPANION_API_HOST) {
                        get("/ping") { call.respondText("pong") }
                    }
                }
            }
            val t = key.token(claims(clock))
            client.get("/api/v1/ping") { header("Authorization", "Bearer $t"); header("Host", "evil.example") }.status shouldBe HttpStatusCode.NotFound
            client.get("/api/v1/ping") { header("Authorization", "Bearer $t"); header("Host", COMPANION_API_HOST) }.status shouldBe HttpStatusCode.OK
        }
    }
})
