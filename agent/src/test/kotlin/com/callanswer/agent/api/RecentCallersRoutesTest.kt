package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.Config
import com.callanswer.agent.agentModule
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private class RcClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

class RecentCallersRoutesTest : StringSpec({
    val clock = RcClock()
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
    val items = InMemoryItemStore()
    val blobs = InMemoryBlobStore()
    val repoFor = { u: UserId -> UserScopedRepository(u, items, blobs) }

    fun ApplicationTestBuilder.setup() = application {
        agentModule(Config.fromEnv(emptyMap())) {
            companionApi(JwtVerifier(JwtVerifier.POOL_ISSUER, "client-1", { jwks }, clock), RateLimiter(), profileActiveCheck(repoFor)) {
                recentCallersRoutes(repoFor, clock)
            }
        }
    }

    suspend fun ApplicationTestBuilder.get(path: String, sub: String): HttpResponse =
        client.get("/api/v1$path") { header("Authorization", "Bearer ${token(sub)}") }

    "returns only the User's records within the window, and 400 for an invalid minutes" {
        val min = 60_000L
        val recent = E164("+919812345678")
        val old = E164("+919800000001")
        val foreign = E164("+919800000002")
        repoFor(UserId("rc-a")).recordEnded(recent, clock.now - 10 * min, IntentCategory.DELIVERY, null)
        repoFor(UserId("rc-a")).recordEnded(old, clock.now - 40 * min, IntentCategory.UNKNOWN, null)
        repoFor(UserId("rc-b")).recordEnded(foreign, clock.now - 1 * min, IntentCategory.DELIVERY, null)
        testApplication {
            setup()
            fun numbers(body: String) =
                Json.parseToJsonElement(body).jsonObject["callers"]!!.jsonArray.map { it.jsonObject["number"]!!.jsonPrimitive.content }
            val all = get("/recent-callers", "rc-a")
            all.status shouldBe HttpStatusCode.OK
            val first = Json.parseToJsonElement(all.bodyAsText()).jsonObject["callers"]!!.jsonArray[0].jsonObject
            first["endMs"]!!.jsonPrimitive.content shouldBe (clock.now - 10 * min).toString()
            first["intent"]!!.jsonPrimitive.content shouldBe "DELIVERY"
            numbers(all.bodyAsText()) shouldBe listOf(recent.value, old.value)
            numbers(get("/recent-callers?minutes=15", "rc-a").bodyAsText()) shouldBe listOf(recent.value)
            for (bad in listOf("0", "61", "abc", "-5")) {
                get("/recent-callers?minutes=$bad", "rc-a").status shouldBe HttpStatusCode.BadRequest
            }
        }
    }
})
