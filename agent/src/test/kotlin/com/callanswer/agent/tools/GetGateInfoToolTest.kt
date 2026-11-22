package com.callanswer.agent.tools

import com.callanswer.agent.Clock
import com.callanswer.core.gate.GateInfoRenderer
import com.callanswer.core.gate.GateSelector
import com.callanswer.core.model.DefaultGates
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import java.time.ZonedDateTime

class GetGateInfoToolTest : FunSpec({
    fun clockOf(f: () -> Long) = object : Clock { override fun nowMillis() = f() }
    fun at(h: Int, m: Int) = clockOf { ZonedDateTime.of(2025, 1, 15, h, m, 30, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli() }
    fun state(stalling: Boolean = false) = MutableCallState("u1", FrozenSettings(), stallingMode = stalling)
    fun args(vararg p: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*p))

    test("night start selects gate 3, night end selects gate 1, text equals renderer") {
        val night = GetGateInfoTool(at(22, 30)).execute(args("language" to JsonPrimitive("en")), state())
        night["gate_number"]!!.jsonPrimitive.content shouldBe "3"
        night["speak_exactly"]!!.jsonPrimitive.content shouldBe
            GateInfoRenderer.render(GateSelector.selectGate(LocalTimeHm(22, 30), DefaultGates), Language.EN, false)
        val day = GetGateInfoTool(at(5, 30)).execute(args("language" to JsonPrimitive("hi")), state())
        day["gate_number"]!!.jsonPrimitive.content shouldBe "1"
    }

    test("window times only on request") {
        val without = GetGateInfoTool(at(12, 0)).execute(args(), state())["speak_exactly"]!!.jsonPrimitive.content
        without shouldNotContain "night window"
        val with = GetGateInfoTool(at(12, 0)).execute(args("language" to JsonPrimitive("en"), "include_window_times" to JsonPrimitive(true)), state())
        with["speak_exactly"]!!.jsonPrimitive.content shouldContain "night window"
    }

    test("clock failure returns both gates, UNAVAILABLE, and a note") {
        val notes = mutableListOf<String>()
        val bad = clockOf { throw IllegalStateException("no clock") }
        val r = GetGateInfoTool(bad, { _, t -> notes += t }).execute(args("language" to JsonPrimitive("en")), state())
        r["clock"]!!.jsonPrimitive.content shouldBe "UNAVAILABLE"
        val text = r["speak_exactly"]!!.jsonPrimitive.content
        text shouldContain "Gate 1"
        text shouldContain "Gate 3"
        text shouldContain "night window"
        notes.size shouldBe 1
    }

    test("stalling mode returns no real gate data") {
        val r = GetGateInfoTool(at(12, 0)).execute(args("language" to JsonPrimitive("en")), state(stalling = true))
        r.containsKey("gate_number") shouldBe false
        r["speak_exactly"]!!.jsonPrimitive.content shouldNotContain "Press Enclave"
    }

    test("property: every minute matches the selector and renderer") {
        checkAll(Arb.int(0, 23), Arb.int(0, 59)) { h, m ->
            val r = GetGateInfoTool(at(h, m)).execute(args("language" to JsonPrimitive("hi")), state())
            r["speak_exactly"]!!.jsonPrimitive.content shouldBe
                GateInfoRenderer.render(GateSelector.selectGate(LocalTimeHm(h, m), DefaultGates), Language.HI, false)
        }
    }
})
