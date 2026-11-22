package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.Config
import com.callanswer.agent.agentModule
import com.callanswer.agent.store.DirectoryRepository
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.NumberAssignmentService
import com.callanswer.agent.store.UserScopedRepository
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

private class AdminClock(val now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

private fun enc(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

private class GroupSigner {
    val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    fun jwks(): String {
        val pub = pair.public as RSAPublicKey
        fun u(v: java.math.BigInteger) = enc(v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        return """{"keys":[{"kty":"RSA","kid":"k1","alg":"RS256","use":"sig","n":"${u(pub.modulus)}","e":"${u(pub.publicExponent)}"}]}"""
    }
    fun token(sub: String, nowMs: Long, groups: List<String>): String {
        val h = enc("""{"alg":"RS256","kid":"k1"}""".toByteArray())
        val g = groups.joinToString(",", "[", "]") { "\"$it\"" }
        val c = enc("""{"sub":"$sub","iss":"${JwtVerifier.POOL_ISSUER}","aud":"client-1","token_use":"id","exp":${nowMs / 1000 + 3600},"cognito:groups":$g}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update("$h.$c".toByteArray()); sign() }
        return "$h.$c.${enc(sig)}"
    }
}

class AdminRoutesTest : StringSpec({
    val clock = AdminClock()
    val signer = GroupSigner()
    val items = InMemoryItemStore()
    val blobs = InMemoryBlobStore()
    val directory = DirectoryRepository(items)
    val repoFor = { u: UserId -> UserScopedRepository(u, items, blobs) }
    val deps = AdminRouteDeps(NumberAssignmentService(items), directory, repoFor)

    fun ApplicationTestBuilder.setup() = application {
        agentModule(Config.fromEnv(emptyMap())) {
            companionApi(JwtVerifier(JwtVerifier.POOL_ISSUER, "client-1", { signer.jwks() }, clock), RateLimiter(), profileActiveCheck(repoFor)) {
                adminRoutes(deps)
            }
        }
    }

    suspend fun ApplicationTestBuilder.call(method: String, path: String, groups: List<String>, body: String? = null): HttpResponse {
        val t = signer.token("admin-1", clock.now, groups)
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

    val admin = listOf("admins")

    "non-admin tokens get 403 on every admin route" {
        testApplication {
            setup()
            call("GET", "/admin/users", emptyList()).status shouldBe HttpStatusCode.Forbidden
            call("GET", "/admin/numbers", listOf("staff")).status shouldBe HttpStatusCode.Forbidden
            call("PUT", "/admin/numbers/+918000000001", emptyList(), """{"userId":"u-1"}""").status shouldBe HttpStatusCode.Forbidden
            call("DELETE", "/admin/numbers/+918000000001", emptyList()).status shouldBe HttpStatusCode.Forbidden
            call("POST", "/admin/users/u-1/deactivate", emptyList()).status shouldBe HttpStatusCode.Forbidden
        }
    }

    "assign, list, conflict 409, unassign, and deactivate" {
        testApplication {
            setup()
            call("PUT", "/admin/numbers/+918000000001", admin, """{"userId":"u-1"}""").status shouldBe HttpStatusCode.OK
            call("PUT", "/admin/numbers/+918000000001", admin, """{"userId":"u-2"}""").status shouldBe HttpStatusCode.Conflict
            call("PUT", "/admin/numbers/+918000000002", admin, """{"userId":"u-1"}""").status shouldBe HttpStatusCode.Conflict
            call("PUT", "/admin/numbers/+918000000003", admin, "{}").status shouldBe HttpStatusCode.BadRequest
            call("PUT", "/admin/numbers/abc", admin, """{"userId":"u-2"}""").status shouldBe HttpStatusCode.BadRequest

            val numbers = Json.parseToJsonElement(call("GET", "/admin/numbers", admin).bodyAsText()).jsonObject["numbers"]!!.jsonArray
            numbers.size shouldBe 1
            numbers[0].jsonObject["userId"]!!.jsonPrimitive.content shouldBe "u-1"

            call("DELETE", "/admin/numbers/+918000000001", admin).status shouldBe HttpStatusCode.NoContent
            call("DELETE", "/admin/numbers/+918000000001", admin).status shouldBe HttpStatusCode.NotFound

            repoFor(UserId("u-1")).createProfileIfAbsent(1L)
            call("POST", "/admin/users/u-1/deactivate", admin).status shouldBe HttpStatusCode.NoContent
            call("POST", "/admin/users/nobody/deactivate", admin).status shouldBe HttpStatusCode.NotFound
            val users = Json.parseToJsonElement(call("GET", "/admin/users", admin).bodyAsText()).jsonObject["users"]!!.jsonArray
            users.single { it.jsonObject["userId"]!!.jsonPrimitive.content == "u-1" }.jsonObject["status"]!!.jsonPrimitive.content shouldBe "DEACTIVATED"
        }
    }
})
