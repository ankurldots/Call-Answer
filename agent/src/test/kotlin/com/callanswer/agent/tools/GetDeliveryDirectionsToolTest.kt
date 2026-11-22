package com.callanswer.agent.tools

import com.callanswer.agent.Clock
import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Language
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class GetDeliveryDirectionsToolTest : FunSpec({
    val clock = object : Clock { override fun nowMillis() = 1_700_000_000_000L }
    fun args(lang: String) = buildJsonObject { put("language", JsonPrimitive(lang)) }
    fun text(r: JsonObject) = (r["speak_exactly"] as JsonPrimitive).content
    val steps = listOf(
        DeliveryStep("a", "Come in.", "अंदर आइए।"),
        DeliveryStep("b", "Go to {map_location}.", "{map_location} पर जाइए।"),
    )

    test("gate info first, steps in order, placeholder replaced") {
        val tool = GetDeliveryDirectionsTool(clock, Language.EN)
        val st = MutableCallState("u", FrozenSettings(deliverySteps = steps, mapLocation = "Block C"))
        val r = tool.execute(args("en"), st)
        r["status"] shouldBe JsonPrimitive("OK")
        val t = text(r)
        t shouldContain "Gate"
        t.indexOf("Come in.") shouldBe t.indexOf("Come in.").also { (it > t.indexOf("Gate")) shouldBe true }
        t shouldContain "Go to Block C."
        t shouldNotContain "{map_location}"
        r["repetition"] shouldBe JsonPrimitive(0)
        r["repetitions_left"] shouldBe JsonPrimitive(3)
    }

    test("fourth call is OK, fifth is REPETITION_LIMIT") {
        val tool = GetDeliveryDirectionsTool(clock, Language.EN)
        val st = MutableCallState("u", FrozenSettings(deliverySteps = steps, mapLocation = "x"))
        repeat(4) { tool.execute(args("en"), st)["status"] shouldBe JsonPrimitive("OK") }
        val r = tool.execute(args("en"), st)
        r["status"] shouldBe JsonPrimitive("REPETITION_LIMIT")
        r["then"] shouldBe JsonPrimitive("end_call")
    }

    test("missing map location requests a push and leaves no placeholder") {
        val pushed = mutableListOf<String>()
        val tool = GetDeliveryDirectionsTool(clock, Language.EN, { _, k -> pushed += k })
        val st = MutableCallState("u", FrozenSettings(deliverySteps = steps, mapLocation = null))
        val r = tool.execute(args("en"), st)
        pushed shouldBe listOf("MAP_LOCATION_MISSING")
        text(r) shouldNotContain "{map_location}"
    }

    test("single-language step carries the note; other uses default language") {
        val tool = GetDeliveryDirectionsTool(clock, Language.HI)
        val st = MutableCallState("u", FrozenSettings(deliverySteps = listOf(DeliveryStep("a", "Only English.", "")), mapLocation = "x"))
        val r = tool.execute(args("other"), st)
        (r["note"] as JsonPrimitive).content shouldContain "Caller's language"
        text(r) shouldContain "Only English."
        text(r) shouldContain "गेट"
    }
})
