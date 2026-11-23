package com.callanswer.agent.exotel

import com.callanswer.agent.Config
import com.callanswer.agent.agentModule
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.NumberAssignmentService
import com.callanswer.agent.store.TenantResolver
import com.callanswer.agent.store.UnassignedCallLog
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ExotelResolveRouteTest : FunSpec({
    val alice = UserId("alice-sub")
    val bob = UserId("bob-sub")
    val auth = ExotelAuth({ mapOf("web" to "exotel:pw", "tok" to "key-1")[it] }, "web", "tok")
    val basic = "Basic " + Base64.getEncoder().encodeToString("exotel:pw".toByteArray())

    suspend fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) {
        val store = InMemoryItemStore()
        val clock = { 1_700_000_000_000L }
        val resolver = TenantResolver(store, UnassignedCallLog(store, clock), clock)
        val svc = NumberAssignmentService(store, resolver, clock)
        UserScopedRepository(alice, store, InMemoryBlobStore()).createProfileIfAbsent(1)
        UserScopedRepository(bob, store, InMemoryBlobStore()).createProfileIfAbsent(1)
        svc.assign("+919800000001", alice, "admin")
        svc.assign("+919800000002", bob, "admin")
        svc.assign("+919800000003", UserId("carol-sub").also {
            UserScopedRepository(it, store, InMemoryBlobStore()).createProfileIfAbsent(1)
        }, "admin")
        svc.deactivate(UserId("carol-sub"))
        val deps = ExotelRouteDeps(auth, resolver, "agent.example.com", 8000)
        testApplication {
            application { agentModule(Config.fromEnv(emptyMap()), exotel = { exotelResolveRoute(deps) }) }
            block()
        }
    }

    fun userOf(body: String, callSid: String): String? {
        val url = Json.parseToJsonElement(body).jsonObject["url"]!!.jsonPrimitive.content
        url shouldStartWith "wss://agent.example.com/stream/"
        url.substringAfter("?") shouldBe "sample-rate=8000"
        val token = url.substringAfter("/stream/").substringBefore("?")
        return auth.verifyCallToken(token, callSid)
    }

    test("missing or wrong Basic header gives 401 on GET and POST") {
        withApp {
            client.get("/exotel/resolve?CallSid=CA1&CallTo=%2B919800000001").status shouldBe HttpStatusCode.Unauthorized
            client.post("/exotel/resolve") { header(HttpHeaders.Authorization, "Basic AAAA") }.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("assigned CallTo returns a URL whose token verifies for the call and the assigned user") {
        withApp {
            val r = client.get("/exotel/resolve?CallSid=CA1&CallTo=%2B919800000001") { header(HttpHeaders.Authorization, basic) }
            r.status shouldBe HttpStatusCode.OK
            userOf(r.bodyAsText(), "CA1") shouldBe "alice-sub"
            val p = client.post("/exotel/resolve") {
                header(HttpHeaders.Authorization, basic)
                header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
                setBody("CallSid=CA2&CallTo=%2B919800000002")
            }
            p.status shouldBe HttpStatusCode.OK
            userOf(p.bodyAsText(), "CA2") shouldBe "bob-sub"
        }
    }

    test("From, CallFrom and ForwardedFrom never change the resolved user") {
        withApp {
            val r = client.get(
                "/exotel/resolve?CallSid=CA1&CallTo=%2B919800000001&From=%2B919800000002&CallFrom=%2B919800000002&ForwardedFrom=%2B919800000002",
            ) { header(HttpHeaders.Authorization, basic) }
            userOf(r.bodyAsText(), "CA1") shouldBe "alice-sub"
            val noTo = client.get("/exotel/resolve?CallSid=CA1&From=%2B919800000001&ForwardedFrom=%2B919800000001") {
                header(HttpHeaders.Authorization, basic)
            }
            noTo.status shouldBe HttpStatusCode.NotFound
        }
    }

    test("unassigned, invalid and deactivated numbers give 404 OUT_OF_SERVICE without a URL") {
        withApp {
            for (to in listOf("%2B919811111111", "abc", "%2B919800000003")) {
                val r = client.get("/exotel/resolve?CallSid=CA1&CallTo=$to") { header(HttpHeaders.Authorization, basic) }
                r.status shouldBe HttpStatusCode.NotFound
                r.bodyAsText() shouldBe """{"error":"OUT_OF_SERVICE"}"""
            }
        }
    }

    test("number format variants resolve to the same user and a missing CallSid is a 400") {
        withApp {
            for (to in listOf("%2B91%209800000001", "09800000001", "9800000001", "919800000001")) {
                val r = client.get("/exotel/resolve?CallSid=CA1&To=$to") { header(HttpHeaders.Authorization, basic) }
                userOf(r.bodyAsText(), "CA1") shouldBe "alice-sub"
            }
            client.get("/exotel/resolve?CallTo=%2B919800000001") { header(HttpHeaders.Authorization, basic) }
                .status shouldBe HttpStatusCode.BadRequest
        }
    }
})
