package com.callanswer.agent.tools

import com.callanswer.core.model.FrozenSettings
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class TakeoverToolsTest : StringSpec({
    fun state() = MutableCallState("u1", FrozenSettings())

    "request_takeover calls the sink and returns OK" {
        var calls = 0
        val r = ToolArgs.run(RequestTakeoverTool { calls++ }, JsonObject(emptyMap()), state())
        r["status"] shouldBe JsonPrimitive("OK")
        calls shouldBe 1
    }

    "end_call forwards a valid reason and rejects an unknown one" {
        var got: String? = null
        val tool = EndCallTool { _, reason -> got = reason }
        ToolArgs.run(tool, buildJsonObject { put("reason", JsonPrimitive("DELIVERY_DONE")) }, state())["status"] shouldBe JsonPrimitive("OK")
        got shouldBe "DELIVERY_DONE"
        got = null
        ToolArgs.run(tool, buildJsonObject { put("reason", JsonPrimitive("BOGUS")) }, state())["status"] shouldBe JsonPrimitive("REJECTED")
        got shouldBe null
    }

    "end action waits for the last mark" {
        val e = EndAfterPlayback()
        var ended = 0
        e.markSent(); e.markSent()
        e.request { ended++ }
        ended shouldBe 0
        e.markPlayed()
        ended shouldBe 0
        e.markPlayed()
        ended shouldBe 1
        e.markPlayed()
        ended shouldBe 1
    }

    "end action runs at once when no mark is pending" {
        var ended = 0
        EndAfterPlayback().request { ended++ }
        ended shouldBe 1
    }
})
