package com.callanswer.agent.obs

import com.callanswer.agent.Clock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private class ManualClock(var now: Long) : Clock {
    override fun nowMillis() = now
}

class ObsTest : FunSpec({
    test("trace records events relative to start with 30-day ttl") {
        val clock = ManualClock(1_000_000)
        val trace = CallTrace("user-1", "call-1", clock)
        clock.now += 250
        trace.record(TraceEventName.TOOL_CALL, "lookup", "OK", durationMs = 40)
        trace.recordError("sonic", "THROTTLED")
        val ev = trace.events()
        ev[0].tMs shouldBe 250
        ev[1].errorCode shouldBe "THROTTLED"
        trace.ttlEpochSeconds() shouldBe (1_000_000L + 30L * 86_400_000L) / 1000
        var written: List<TraceEvent>? = null
        trace.persist(object : TraceSink {
            override fun write(userId: String, callId: String, events: List<TraceEvent>, ttlEpochSeconds: Long) {
                written = events
            }
        })
        written?.size shouldBe 2
    }

    test("phone numbers and free text cannot enter trace or log") {
        val clock = ManualClock(0)
        val lines = mutableListOf<String>()
        val trace = CallTrace("u", "c", clock, StructuredLog(clock) { lines += it })
        val e = trace.record(TraceEventName.TOOL_CALL, "+919876543210", "hello I need my Protected Term")
        e.component shouldBe "INVALID"
        e.status shouldBe "INVALID"
        lines.joinToString() shouldNotContain "9876543210"
        lines.joinToString() shouldNotContain "Protected"
        Json.parseToJsonElement(lines[0]).jsonObject["event"]!!.jsonPrimitive.content shouldBe "TOOL_CALL"
    }

    test("metrics are embedded metric format lines") {
        val lines = mutableListOf<String>()
        val m = Metrics(ManualClock(5), out = { lines += it })
        m.callStarted()
        m.timeToFirstAudio(900)
        m.replyLatency(700)
        m.fallback()
        m.sonicError()
        m.activeCalls(2)
        lines.size shouldBe 6
        lines[1] shouldContain "\"TimeToFirstAudioMs\":900.0"
        lines[1] shouldContain "CloudWatchMetrics"
        lines[5] shouldContain "\"ActiveCalls\""
    }
})
