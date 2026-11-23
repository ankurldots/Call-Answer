package com.callanswer.agent.notify

import com.callanswer.agent.Clock
import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.store.DeviceRecord
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private class TestClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis() = now
}

private class FakeGoogle(val tokenStatus: Int = 200, val expiresIn: Long = 3600) {
    val tokenCalls = mutableListOf<String>()
    val sends = mutableListOf<Pair<String, String>>() // url, body
    val sendHeaders = mutableListOf<Map<String, String>>()
    var reply: (String) -> HttpReply = { HttpReply(200, """{"name":"projects/p/messages/1"}""") }

    val poster = HttpPoster { url, headers, body ->
        if (url.startsWith("https://oauth2.googleapis.com/token")) {
            tokenCalls += body
            HttpReply(tokenStatus, """{"access_token":"at-${tokenCalls.size}","expires_in":$expiresIn,"token_type":"Bearer"}""")
        } else {
            sends += url to body
            sendHeaders += headers
            val token = Json.parseToJsonElement(body).jsonObject["message"]!!.jsonObject["token"]!!.jsonPrimitive.content
            reply(token)
        }
    }
}

private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
private val pem = "-----BEGIN PRIVATE KEY-----\n" +
    Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
private val saJson = Json.encodeToString(
    kotlinx.serialization.json.JsonObject.serializer(),
    kotlinx.serialization.json.buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("service_account"))
        put("project_id", kotlinx.serialization.json.JsonPrimitive("call-answer-f43ef"))
        put("client_email", kotlinx.serialization.json.JsonPrimitive("fcm@call-answer-f43ef.iam.gserviceaccount.com"))
        put("private_key", kotlinx.serialization.json.JsonPrimitive(pem))
    },
)

private fun errorBody(code: Int, status: String, fcmCode: String? = null) =
    """{"error":{"code":$code,"status":"$status","message":"x"${if (fcmCode != null) ""","details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"$fcmCode"}]""" else ""}}}"""

class AlertServiceTest : FunSpec({
    val alice = UserId("alice-1")
    val bob = UserId("bob-2")

    class Env(val google: FakeGoogle = FakeGoogle(), val clock: TestClock = TestClock()) {
        val items = InMemoryItemStore()
        val blobs = InMemoryBlobStore()
        val secretReads = mutableListOf<String>()
        val fcm = FcmClient({ secretReads += it; if (it == "callagent/fcm") saJson else null }, "callagent/fcm", google.poster, clock)
        fun repo(u: UserId) = UserScopedRepository(u, items, blobs)
        val alerts = AlertService(fcm, ::repo)
    }

    suspend fun Env.devices(u: UserId, vararg ids: String) = ids.forEach { repo(u).putDevice(DeviceRecord(it, "tok-$it", 1)) }

    test("sends a high-priority data message to every device of the owner only") {
        val e = Env()
        e.devices(alice, "a1", "a2"); e.devices(bob, "b1")
        runTest {
            val out = e.alerts.send(alice, PushMessages.sendMapSms("call-1", "+919812345678", "Map: http://x"))
            out shouldBe PushOutcome(2, 2, 0, null)
        }
        e.google.sends.size shouldBe 2
        e.google.sends.all { it.first == "https://fcm.googleapis.com/v1/projects/call-answer-f43ef/messages:send" } shouldBe true
        val msgs = e.google.sends.map { Json.parseToJsonElement(it.second).jsonObject["message"]!!.jsonObject }
        msgs.map { it["token"]!!.jsonPrimitive.content }.sorted() shouldContainExactly listOf("tok-a1", "tok-a2")
        msgs.forEach {
            it["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content shouldBe "HIGH"
            it.containsKey("notification") shouldBe false
            val d = it["data"]!!.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
            d shouldBe mapOf("type" to "send_map_sms", "callId" to "call-1", "number" to "+919812345678", "text" to "Map: http://x")
        }
        e.google.sendHeaders.first()["Authorization"] shouldBe "Bearer at-1"
    }

    test("recent_caller data contract omits an absent callId") {
        PushMessages.recentCaller("+919812345678", 5L, "DELIVERY", null).toData() shouldBe
            mapOf("type" to "recent_caller", "number" to "+919812345678", "endMs" to "5", "intent" to "DELIVERY")
        PushMessages.recentCaller("+919812345678", 5L, "DELIVERY", "c1").toData()["callId"] shouldBe "c1"
    }

    test("the access token is a valid RS256 JWT bearer assertion and is cached until near expiry") {
        val e = Env()
        e.devices(alice, "a1")
        runTest {
            e.alerts.send(alice, PushMessages.piiEvent("c1"))
            e.alerts.send(alice, PushMessages.piiEvent("c2"))
        }
        e.google.tokenCalls.size shouldBe 1
        val form = e.google.tokenCalls.single().split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        form["grant_type"] shouldBe "urn:ietf:params:oauth:grant-type:jwt-bearer"
        val (h, c, s) = form["assertion"]!!.split('.')
        val dec = Base64.getUrlDecoder()
        val claims = Json.parseToJsonElement(String(dec.decode(c))).jsonObject
        claims["iss"]!!.jsonPrimitive.content shouldBe "fcm@call-answer-f43ef.iam.gserviceaccount.com"
        claims["scope"]!!.jsonPrimitive.content shouldBe FcmClient.SCOPE
        claims["aud"]!!.jsonPrimitive.content shouldBe "https://oauth2.googleapis.com/token"
        Signature.getInstance("SHA256withRSA").run {
            initVerify(keyPair.public); update("$h.$c".toByteArray()); verify(dec.decode(s))
        } shouldBe true

        e.clock.now += 3600_000L - 61_000L // still cached
        runTest { e.alerts.send(alice, PushMessages.piiEvent("c3")) }
        e.google.tokenCalls.size shouldBe 1
        e.clock.now += 2_000L // inside the 60 s margin: refresh
        runTest { e.alerts.send(alice, PushMessages.piiEvent("c4")) }
        e.google.tokenCalls.size shouldBe 2
        e.google.sendHeaders.last()["Authorization"] shouldBe "Bearer at-2"
    }

    test("UNREGISTERED and INVALID_ARGUMENT remove the token; other failures keep it") {
        val e = Env()
        e.devices(alice, "gone", "bad", "ok", "down")
        e.google.reply = {
            when (it) {
                "tok-gone" -> HttpReply(404, errorBody(404, "NOT_FOUND", "UNREGISTERED"))
                "tok-bad" -> HttpReply(400, errorBody(400, "INVALID_ARGUMENT"))
                "tok-down" -> HttpReply(503, errorBody(503, "UNAVAILABLE"))
                else -> HttpReply(200, "{}")
            }
        }
        runTest {
            val out = e.alerts.send(alice, PushMessages.piiEvent("c1"))
            out.accepted shouldBe 1
            out.removed shouldBe 2
            e.repo(alice).listDevices().map { it.deviceId }.sorted() shouldContainExactly listOf("down", "ok")
        }
    }

    test("a 401 refreshes the access token once and retries") {
        val e = Env()
        e.devices(alice, "a1")
        var first = true
        e.google.reply = { if (first) { first = false; HttpReply(401, errorBody(401, "UNAUTHENTICATED")) } else HttpReply(200, "{}") }
        runTest { e.alerts.send(alice, PushMessages.piiEvent("c1")).delivered shouldBe true }
        e.google.tokenCalls.size shouldBe 2
    }

    test("a token endpoint failure or missing secret is a failure, not an exception") {
        val e = Env(FakeGoogle(tokenStatus = 400))
        e.devices(alice, "a1")
        runTest { e.alerts.send(alice, PushMessages.piiEvent("c1")) shouldBe PushOutcome(1, 0, 0, "FCM_AUTH_FAILED") }
        e.google.sends.size shouldBe 0
        val noSecret = FcmClient({ null }, "callagent/fcm", FakeGoogle().poster, TestClock())
        runTest { noSecret.send("t", PushMessages.piiEvent("c")) shouldBe FcmResult.Failed("FCM_NO_SECRET") }
    }

    test("takeover alert: acknowledged within 10 s gives ALERT_SENT; the ack of another user does not count") {
        val e = Env()
        e.devices(alice, "a1")
        runTest {
            val r = async { e.alerts.sendTakeoverAlert(alice, PushMessages.takeoverAlert("c1", "+919812345678", "DELIVERY", 1), "c1") }
            runCurrent()
            e.alerts.acknowledge(bob, "c1") shouldBe false
            advanceTimeBy(9_000)
            e.alerts.acknowledge(alice, "c1") shouldBe true
            r.await() shouldBe TakeoverAlertStatus.ALERT_SENT
            e.alerts.acknowledge(alice, "c1") shouldBe false
        }
    }

    test("takeover alert: no acknowledgment after 10 s gives ALERT_UNDELIVERED and a trace entry") {
        val e = Env()
        e.devices(alice, "a1")
        val trace = CallTrace("alice-1", "c1", e.clock)
        runTest {
            val r = async { e.alerts.sendTakeoverAlert(alice, PushMessages.takeoverAlert("c1", null, "DELIVERY", 1), "c1", trace) }
            advanceTimeBy(9_999); runCurrent(); r.isCompleted shouldBe false
            advanceTimeBy(1); runCurrent()
            r.await() shouldBe TakeoverAlertStatus.ALERT_UNDELIVERED
            currentTime shouldBe 10_000
        }
        trace.events().single().errorCode shouldBe "NO_ACK"
    }

    test("takeover alert: no device or FCM rejection returns at once with ALERT_UNDELIVERED and sends nothing else") {
        val e = Env()
        val trace = CallTrace("alice-1", "c1", e.clock)
        runTest {
            e.alerts.sendTakeoverAlert(alice, PushMessages.takeoverAlert("c1", null, "X", 1), "c1", trace) shouldBe TakeoverAlertStatus.ALERT_UNDELIVERED
            currentTime shouldBe 0
        }
        trace.events().single().errorCode shouldBe "NO_DEVICE"
        e.devices(alice, "a1")
        e.google.reply = { HttpReply(500, errorBody(500, "INTERNAL")) }
        val t2 = CallTrace("alice-1", "c2", e.clock)
        runTest {
            e.alerts.sendTakeoverAlert(alice, PushMessages.takeoverAlert("c2", null, "X", 1), "c2", t2) shouldBe TakeoverAlertStatus.ALERT_UNDELIVERED
            currentTime shouldBe 0
        }
        t2.events().single().errorCode shouldBe "FCM_HTTP_500"
        e.google.sends.all { it.first.startsWith("https://fcm.googleapis.com/") } shouldBe true
    }
})
