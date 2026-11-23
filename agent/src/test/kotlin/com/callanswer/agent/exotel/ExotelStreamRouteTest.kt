package com.callanswer.agent.exotel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.withTimeout

class ExotelStreamRouteTest : FunSpec({
    suspend fun refused(env: StreamEnv, client: io.ktor.client.HttpClient, token: String, basic: String?): Boolean =
        runCatching {
            client.webSocket(env.path(token), request = { basic?.let { header(HttpHeaders.Authorization, it) } }) { }
        }.isFailure

    test("missing or wrong Basic header refuses the upgrade and starts no call") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            val c = wsClient()
            refused(env, c, env.token(), null) shouldBe true
            refused(env, c, env.token(), "Basic AAAA") shouldBe true
            calls.created shouldBe emptyList()
        }
    }

    test("garbage, tampered and expired tokens refuse the upgrade and start no call") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            val c = wsClient()
            refused(env, c, "garbage", env.basic) shouldBe true
            val good = env.token()
            refused(env, c, good.dropLast(2) + "xx", env.basic) shouldBe true
            env.clock.now += 61_000
            refused(env, c, good, env.basic) shouldBe true
            calls.created shouldBe emptyList()
        }
    }

    test("a valid handshake and start create the call for the token's user") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(startEvent())
                sendEvent(ExotelEvent.Stop("s1", "CA1", "callended"))
            }
            waitUntil { calls.call.ends.isNotEmpty() } shouldBe true
            calls.created shouldBe listOf("alice-sub/CA1")
        }
    }

    test("a start whose call_sid differs from the token's closes with VIOLATED_POLICY and starts no call") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(startEvent(callSid = "OTHER"))
                val close = withTimeout(3000) { closeReason.await() }
                close?.knownReason shouldBe CloseReason.Codes.VIOLATED_POLICY
            }
            calls.created shouldBe emptyList()
        }
    }

    test("a start whose called number belongs to another user closes with VIOLATED_POLICY and starts no call") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(startEvent(to = BOB_NUMBER))
                val close = withTimeout(3000) { closeReason.await() }
                close?.knownReason shouldBe CloseReason.Codes.VIOLATED_POLICY
            }
            calls.created shouldBe emptyList()
        }
    }
})
