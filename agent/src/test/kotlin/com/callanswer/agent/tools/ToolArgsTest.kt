package com.callanswer.agent.tools

import com.callanswer.agent.sonic.ToolSpecs
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class ToolArgsTest : FunSpec({
    fun spec(n: String) = ToolSpecs.all.first { it.name == n }
    fun obj(vararg p: Pair<String, JsonPrimitive>) = JsonObject(mapOf(*p))

    test("valid and invalid arguments") {
        ToolArgs.validate(spec("log_note"), obj("kind" to JsonPrimitive("INTENT"), "value" to JsonPrimitive("DELIVERY"))) shouldBe ArgCheck.Valid
        ToolArgs.validate(spec("get_gate_info"), JsonObject(emptyMap())) shouldBe ArgCheck.Valid
        ToolArgs.validate(spec("get_gate_info"), obj("include_window_times" to JsonPrimitive(true))) shouldBe ArgCheck.Valid
        ToolArgs.validate(spec("log_note"), obj("kind" to JsonPrimitive("INTENT"))).shouldBeInstanceOf<ArgCheck.Invalid>().field shouldBe "value"
        ToolArgs.validate(spec("log_note"), obj("kind" to JsonPrimitive("X"), "value" to JsonPrimitive("a"))).shouldBeInstanceOf<ArgCheck.Invalid>().field shouldBe "kind"
        ToolArgs.validate(spec("get_gate_info"), obj("include_window_times" to JsonPrimitive("yes"))).shouldBeInstanceOf<ArgCheck.Invalid>()
        ToolArgs.validate(spec("send_map_sms"), obj("number" to JsonPrimitive("9"))).shouldBeInstanceOf<ArgCheck.Invalid>().message shouldBe "unknown field"
    }

    test("run returns REJECTED for invalid arguments without executing") {
        var called = false
        val tool = object : AgentTool {
            override val spec = spec("end_call")
            override suspend fun execute(args: JsonObject, callState: CallState): JsonObject { called = true; return ToolResults.ok() }
        }
        val r = ToolArgs.run(tool, buildJsonObject { put("reason", JsonPrimitive("NOPE")) }, MutableCallState("u", com.callanswer.core.model.FrozenSettings()))
        called shouldBe false
        r["error"] shouldBe JsonPrimitive("INVALID_ARGUMENT")
    }
})
