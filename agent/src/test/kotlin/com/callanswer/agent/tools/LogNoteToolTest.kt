package com.callanswer.agent.tools

import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class LogNoteToolTest : FunSpec({
    fun state(intent: IntentCategory? = null) = MutableCallState("u1", FrozenSettings(), intent = intent)

    suspend fun call(tool: LogNoteTool, st: CallState, kind: String, value: String): JsonObject =
        ToolArgs.run(tool, buildJsonObject {
            put("kind", JsonPrimitive(kind))
            put("value", JsonPrimitive(value))
        }, st)

    fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content

    test("accepts the four categories and stores them") {
        val tool = LogNoteTool()
        val st = state()
        for (c in IntentCategory.entries) {
            call(tool, st, "INTENT", c.name).str("status") shouldBe "OK"
            st.intent shouldBe c
        }
    }

    // Feature: cloud-call-agent, Property 7: Intent logging accepts only the four categories
    // **Validates: Requirements 8.2, 8.10**
    test("any other intent value is rejected with the current category and the stored category is unchanged") {
        val names = IntentCategory.entries.map { it.name }.toSet()
        val tool = LogNoteTool()
        checkAll(Arb.string(0..20)) { v ->
            val st = state(IntentCategory.SOLICITATION)
            val r = call(tool, st, "INTENT", v)
            if (v in names) {
                r.str("status") shouldBe "OK"
                st.intent shouldBe IntentCategory.valueOf(v)
            } else {
                r.str("status") shouldBe "REJECTED"
                r.str("error") shouldBe "INVALID_INTENT"
                r.str("current") shouldBe "SOLICITATION"
                st.intent shouldBe IntentCategory.SOLICITATION
            }
        }
    }

    test("language, message and note kinds") {
        val seen = mutableListOf<String>()
        val tool = LogNoteTool { _, t -> seen += t }
        val st = state()
        call(tool, st, "LANGUAGE", "en").str("status") shouldBe "OK"
        st.language shouldBe Language.EN
        call(tool, st, "LANGUAGE", "fr").str("status") shouldBe "REJECTED"
        st.language shouldBe Language.EN
        call(tool, st, "MESSAGE", "Ramesh, parcel").str("status") shouldBe "OK"
        call(tool, st, "NOTE", "x".repeat(200)).str("status") shouldBe "OK"
        call(tool, st, "NOTE", "x".repeat(201)).str("status") shouldBe "REJECTED"
        call(tool, st, "MESSAGE", "x".repeat(501)).str("status") shouldBe "REJECTED"
        seen shouldBe listOf("MESSAGE: Ramesh, parcel", "NOTE: " + "x".repeat(200))
    }
})
