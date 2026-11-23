package com.callanswer.agent.notify

import com.callanswer.agent.tools.CallerNumberSource
import com.callanswer.agent.tools.MutableCallState
import com.callanswer.agent.tools.SendMapSmsTool
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Language
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class MapSmsServiceTest : FunSpec({
    val alice = UserId("alice-1")
    val bob = UserId("bob-2")
    val number = "+919812345678"
    val location = "https://maps.app.goo.gl/x"

    class Env(val devices: Int = 1) {
        val sent = mutableListOf<PushMessage>()
        val recorded = mutableListOf<String>()
        /** Called while the request is pushed; the app's answer arrives here. */
        var onRequest: (PushMessage) -> Unit = {}
        lateinit var service: MapSmsService

        fun build(ackWindowMs: Long = 20_000): MapSmsService {
            service = MapSmsService(
                { _, m, _ ->
                    sent += m
                    if (m.type == PushType.SEND_MAP_SMS) onRequest(m)
                    if (devices == 0) PushOutcome(0, 0, 0, "NO_DEVICE") else PushOutcome(devices, devices, 0, null)
                },
                ackWindowMs,
                { _, _, v -> recorded += v },
            )
            return service
        }

        val requests get() = sent.filter { it.type == PushType.SEND_MAP_SMS }
        val alerts get() = sent.filter { it.type == PushType.MAP_SMS_FAILED }
    }

    suspend fun Env.run(callerNumber: String? = number, loc: String? = location, language: Language = Language.EN) =
        service.sendAndAwait(alice, "call-1", callerNumber, loc, language)

    test("SENT result: one request with callId, number and text; no alert") {
        runTest {
            val e = Env().also { it.build() }
            e.onRequest = { e.service.complete(alice, "call-1", MapSmsStatus.SENT) shouldBe true }
            e.run() shouldBe MapSmsOutcome.Sent
            e.requests.single().data shouldBe mapOf("callId" to "call-1", "number" to number, "text" to "Directions to our home: $location")
            e.alerts.size shouldBe 0
            e.recorded shouldBe listOf("SENT")
        }
    }

    test("text is rendered in Hindi for a Hindi caller") {
        runTest {
            val e = Env().also { it.build() }
            e.onRequest = { e.service.complete(alice, "call-1", MapSmsStatus.SENT) }
            e.run(language = Language.HI)
            e.requests.single().data["text"]!! shouldContain location
            e.requests.single().data["text"]!! shouldContain "हमारे घर"
        }
    }

    for (status in listOf(MapSmsStatus.FAILED, MapSmsStatus.NO_PERMISSION)) {
        test("$status result says the location verbally and raises MAP_SMS_FAILED") {
            runTest {
                val e = Env().also { it.build() }
                e.onRequest = { e.service.complete(alice, "call-1", status) }
                e.run() shouldBe MapSmsOutcome.SayVerbally(MapSmsReason.valueOf(status.name))
                e.alerts.single().data shouldBe mapOf("callId" to "call-1", "reason" to status.name)
                e.recorded shouldBe listOf(status.name)
            }
        }
    }

    test("no result within 20 seconds gives NO_ACK and an alert") {
        runTest {
            val e = Env().also { it.build() }
            e.run() shouldBe MapSmsOutcome.SayVerbally(MapSmsReason.NO_ACK)
            currentTime shouldBe 20_000
            e.alerts.size shouldBe 1
            e.recorded shouldBe listOf("NO_ACK")
            e.service.isPending(alice, "call-1") shouldBe false
        }
    }

    test("a result after the window is ignored") {
        runTest {
            val e = Env().also { it.build() }
            e.run()
            e.service.complete(alice, "call-1", MapSmsStatus.SENT) shouldBe false
        }
    }

    test("no device gives NO_DEVICE without waiting") {
        runTest {
            val e = Env(devices = 0).also { it.build() }
            e.run() shouldBe MapSmsOutcome.SayVerbally(MapSmsReason.NO_DEVICE)
            currentTime shouldBe 0
        }
    }

    test("hidden, non-mobile, and unconfigured send nothing") {
        runTest {
            val e = Env().also { it.build() }
            e.run(callerNumber = null) shouldBe MapSmsOutcome.SayVerbally(MapSmsReason.SKIPPED_UNKNOWN_NUMBER)
            e.run(callerNumber = "+911123456789") shouldBe MapSmsOutcome.SayVerbally(MapSmsReason.SKIPPED_NOT_MOBILE)
            e.run(loc = " ") shouldBe MapSmsOutcome.SayVerbally(MapSmsReason.NOT_CONFIGURED)
            e.sent.size shouldBe 0
            e.recorded shouldBe listOf("SKIPPED_UNKNOWN_NUMBER", "SKIPPED_NOT_MOBILE", "NOT_CONFIGURED")
        }
    }

    test("a second call sends nothing and returns AlreadySent; skipped numbers do not use the slot") {
        runTest {
            val e = Env().also { it.build() }
            e.run(callerNumber = null)
            e.onRequest = { e.service.complete(alice, "call-1", MapSmsStatus.SENT) }
            e.run() shouldBe MapSmsOutcome.Sent
            e.run() shouldBe MapSmsOutcome.AlreadySent
            e.requests.size shouldBe 1
        }
    }

    test("a foreign User cannot complete the wait, and a duplicate result is ignored") {
        runTest {
            val e = Env().also { it.build() }
            e.onRequest = {
                e.service.complete(bob, "call-1", MapSmsStatus.SENT) shouldBe false
                e.service.complete(alice, "call-1", MapSmsStatus.FAILED) shouldBe true
                e.service.complete(alice, "call-1", MapSmsStatus.SENT) shouldBe false
            }
            e.run() shouldBe MapSmsOutcome.SayVerbally(MapSmsReason.FAILED)
        }
    }

    test("tool maps outcomes to results and takes the number from the call state") {
        runTest {
            val e = Env().also { it.build() }
            e.onRequest = { e.service.complete(alice, "call-1", MapSmsStatus.SENT) }
            val tool = SendMapSmsTool(e.service, CallerNumberSource { number })
            val state = MutableCallState("alice-1", FrozenSettings(mapLocation = location), Language.EN, callId = "call-1")
            fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content
            tool.execute(JsonObject(emptyMap()), state).s("status") shouldBe "SENT"
            tool.execute(JsonObject(emptyMap()), state).s("status") shouldBe "ALREADY_SENT"
            val hidden = SendMapSmsTool(Env().also { it.build() }.service, CallerNumberSource { null })
            val r = hidden.execute(JsonObject(emptyMap()), state)
            r.s("status") shouldBe "SAY_VERBALLY"
            r.s("reason") shouldBe "SKIPPED_UNKNOWN_NUMBER"
            r.s("speak_exactly")!! shouldContain "directions"
        }
    }
})
