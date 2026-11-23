package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.Config
import com.callanswer.agent.agentModule
import com.callanswer.agent.notify.MapSmsOutcome
import com.callanswer.agent.notify.MapSmsService
import com.callanswer.agent.notify.PushOutcome
import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.model.Language
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class MsClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

class MapSmsResultRoutesTest : StringSpec({
    val clock = MsClock()
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val jwks = (pair.public as RSAPublicKey).let { pub ->
        fun u(v: java.math.BigInteger) = b64(v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        """{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","use":"sig","n":"${u(pub.modulus)}","e":"${u(pub.publicExponent)}"}]}"""
    }
    fun token(sub: String): String {
        val h = b64("""{"alg":"RS256","kid":"k1"}""".toByteArray())
        val c = b64("""{"sub":"$sub","iss":"${JwtVerifier.POOL_ISSUER}","aud":"client-1","token_use":"id","exp":${clock.now / 1000 + 3600}}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update("$h.$c".toByteArray()); sign() }
        return "$h.$c.${b64(sig)}"
    }
    val repoFor = { u: UserId -> UserScopedRepository(u, InMemoryItemStore(), InMemoryBlobStore()) }
    val number = "+919812345678"

    class Env {
        val recorded = mutableListOf<String>()
        val trace = CallTrace("ms-a", "call-1", clock)
        val service = MapSmsService({ _, _, _ -> PushOutcome(1, 1, 0, null) }, 5_000, { _, _, _ -> })
        val deps = MapSmsResultRouteDeps(
            repoFor, service, { _, c, v -> recorded += "$c:$v" },
            traceFor = { _, c -> trace.takeIf { c == "call-1" } },
            isLiveCall = { u, c -> u.sub == "ms-a" && c == "call-1" },
        )
    }

    fun ApplicationTestBuilder.setup(env: Env) = application {
        agentModule(Config.fromEnv(emptyMap())) {
            companionApi(JwtVerifier(JwtVerifier.POOL_ISSUER, "client-1", { jwks }, clock), RateLimiter(), profileActiveCheck(repoFor)) {
                mapSmsResultRoutes(env.deps)
            }
        }
    }

    suspend fun ApplicationTestBuilder.result(sub: String, id: String, body: String): HttpResponse =
        client.post("/api/v1/calls/$id/map-sms-result") {
            header("Authorization", "Bearer ${token(sub)}")
            setBody(body)
        }

    "first result wakes the wait and is stored; the second is ignored" {
        val env = Env()
        testApplication {
            setup(env)
            val waiting = async(Dispatchers.Default) {
                env.service.sendAndAwait(UserId("ms-a"), "call-1", number, "https://maps/x", Language.EN)
            }
            while (!env.service.isPending(UserId("ms-a"), "call-1")) withContext(Dispatchers.Default) { delay(10) }
            result("ms-a", "call-1", """{"status":"SENT"}""").status shouldBe HttpStatusCode.NoContent
            waiting.await() shouldBe MapSmsOutcome.Sent
            result("ms-a", "call-1", """{"status":"FAILED"}""").status shouldBe HttpStatusCode.NoContent
            env.recorded shouldBe listOf("call-1:SENT")
            env.trace.events().count { it.component == "map_sms_result" } shouldBe 1
        }
    }

    "a late result is stored although nothing waits any more" {
        val env = Env()
        testApplication {
            setup(env)
            result("ms-a", "call-1", """{"status":"NO_PERMISSION"}""").status shouldBe HttpStatusCode.NoContent
            env.recorded shouldBe listOf("call-1:NO_PERMISSION")
            env.trace.events().single { it.component == "map_sms_result" }.errorCode shouldBe "LATE"
        }
    }

    "a foreign call identifier answers 404 and stores nothing; an invalid status answers 400" {
        val env = Env()
        testApplication {
            setup(env)
            result("ms-b", "call-1", """{"status":"SENT"}""").status shouldBe HttpStatusCode.NotFound
            result("ms-a", "call-1", """{"status":"DELIVERED"}""").status shouldBe HttpStatusCode.BadRequest
            result("ms-a", "call-1", "not json").status shouldBe HttpStatusCode.BadRequest
            env.recorded shouldBe emptyList()
        }
    }
})
