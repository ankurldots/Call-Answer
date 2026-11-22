package com.callanswer.agent.sonic

import com.callanswer.core.model.Language
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.Utterance
import com.callanswer.core.stalling.PiiGuard
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun fixture(name: String): String =
    SonicEventsTest::class.java.getResourceAsStream("/fixtures/sonic/$name")!!.bufferedReader().readText()

private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

private fun eventName(s: String): String = obj(s)["event"]!!.jsonObject.keys.first()

class SonicEventsTest : FunSpec({
    val prompt = "292d3995-01f8-444e-b586-f7f91850fb3c"

    test("input builders produce the shape of the recorded C-5 fixtures") {
        SonicEvents.sessionStart() shouldBeJson fixture("in-01-sessionStart.json")
        SonicEvents.textContentStart(prompt, "08d12ad0-b23e-4be0-a8e0-2d4c9da90c86", "SYSTEM", false) shouldBeJson
            fixture("in-03-system-contentStart.json")
        SonicEvents.contentEnd(prompt, "08d12ad0-b23e-4be0-a8e0-2d4c9da90c86") shouldBeJson fixture("in-05-contentEnd.json")
        SonicEvents.audioContentStart(prompt, "e293fe69-20db-488f-943e-55e591261537") shouldBeJson
            fixture("in-06-audio-contentStart.json")
        SonicEvents.textContentStart(prompt, "44893ae6-c006-4efd-8461-e1d7ccf581fd", "USER", true) shouldBeJson
            fixture("in-08-crossmodal-contentStart.json")
        SonicEvents.textInput(prompt, "44893ae6-c006-4efd-8461-e1d7ccf581fd",
            SonicEvents.greetingTrigger("Hello, you have reached Ankur's assistant. How can I help you?")) shouldBeJson
            fixture("in-09-crossmodal-textInput.json")
        SonicEvents.toolContentStart(prompt, "a58aae1f-bd35-4a9e-8271-40623ff2ae15", "cf082418-413f-45ff-ab85-154d62d0c2fd") shouldBeJson
            fixture("in-10-tool-contentStart.json")
        SonicEvents.toolResult(prompt, "a58aae1f-bd35-4a9e-8271-40623ff2ae15", """{"status":"OK"}""") shouldBeJson
            fixture("in-11-toolResult.json")
        SonicEvents.audioInput(prompt, "e293fe69-20db-488f-943e-55e591261537", "AAAA").let {
            obj(it)["event"]!!.jsonObject["audioInput"]!!.jsonObject["content"]!!.jsonPrimitive.content shouldBe "AAAA"
        }
    }

    test("promptStart carries the audio config and the seven tools like the fixture") {
        val mine = obj(SonicEvents.promptStart(prompt, "tiffany"))["event"]!!.jsonObject["promptStart"]!!.jsonObject
        val theirs = obj(fixture("in-02-promptStart-tools.json"))["event"]!!.jsonObject["promptStart"]!!.jsonObject
        for (k in listOf("promptName", "textOutputConfiguration", "audioOutputConfiguration", "toolUseOutputConfiguration")) {
            mine[k] shouldBe theirs[k]
        }
        val tools = mine["toolConfiguration"]!!.jsonObject["tools"]!!.jsonArray
        tools shouldHaveSize 7
        tools.map { it.jsonObject["toolSpec"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet() shouldBe ToolSpecs.names
        tools.forEach { Json.parseToJsonElement(it.jsonObject["toolSpec"]!!.jsonObject["inputSchema"]!!.jsonObject["json"]!!.jsonPrimitive.content) }
        mine["toolConfiguration"]!!.jsonObject["toolChoice"] shouldBe theirs["toolConfiguration"]!!.jsonObject["toolChoice"]
    }

    test("start sequence order, with history only after the system prompt") {
        var n = 0
        val ids = SessionIds(prompt, "audio-1")
        val seq = SonicEvents.startSequence(
            ids, "kiara", "SYS",
            listOf(HistoryMessage("USER", "hi"), HistoryMessage("ASSISTANT", "hello")),
        ) { "c${n++}" }
        seq.map { eventName(it) } shouldBe listOf(
            "sessionStart", "promptStart",
            "contentStart", "textInput", "contentEnd",
            "contentStart", "textInput", "contentEnd",
            "contentStart", "textInput", "contentEnd",
            "contentStart",
        )
        val roles = seq.filter { eventName(it) == "contentStart" }
            .map { obj(it)["event"]!!.jsonObject["contentStart"]!!.jsonObject["role"]!!.jsonPrimitive.content }
        roles shouldBe listOf("SYSTEM", "USER", "ASSISTANT", "USER")
        SonicEvents.endSequence(ids).map { eventName(it) } shouldBe listOf("contentEnd", "promptEnd", "sessionEnd")
    }

    test("parses every recorded output fixture") {
        val parsed = (1..20).map { i ->
            val file = java.io.File(SonicEventsTest::class.java.getResource("/fixtures/sonic")!!.toURI())
                .listFiles()!!.first { it.name.startsWith("out-%02d-".format(i)) }
            file.name to SonicEvents.parse(file.readText())
        }.toMap()
        parsed.values.filterIsInstance<SonicOutput.Unknown>() shouldHaveSize 0

        (parsed["out-03-speculative-contentStart.json"] as SonicOutput.ContentStart).let {
            it.role shouldBe "ASSISTANT"; it.generationStage shouldBe "SPECULATIVE"; it.type shouldBe "TEXT"
        }
        (parsed["out-04-speculative-textOutput.json"] as SonicOutput.TextOutput).content shouldContain "Hello"
        (parsed["out-07-audioOutput.json"] as SonicOutput.AudioOutput).base64 shouldBe "AAAAAAAAAAAAAAAAAAAAAA=="
        (parsed["out-10-final-contentStart.json"] as SonicOutput.ContentStart).generationStage shouldBe "FINAL"
        (parsed["out-12-contentEnd-INTERRUPTED.json"] as SonicOutput.ContentEnd).let {
            it.interrupted shouldBe true; it.type shouldBe "TEXT"
        }
        (parsed["out-08-audio-contentEnd.json"] as SonicOutput.ContentEnd).interrupted shouldBe false
        (parsed["out-14-user-final-contentStart.json"] as SonicOutput.ContentStart).role shouldBe "USER"
        (parsed["out-18-toolUse.json"] as SonicOutput.ToolUse).let {
            it.toolName shouldBe "log_note"
            it.toolUseId shouldBe "cf082418-413f-45ff-ab85-154d62d0c2fd"
            it.contentJson shouldBe """{"kind":"INTENT","value":"DELIVERY"}"""
        }
        (parsed["out-19-contentEnd-TOOL_USE.json"] as SonicOutput.ContentEnd).stopReason shouldBe "TOOL_USE"
        (parsed["out-02-usageEvent.json"] as SonicOutput.Usage) shouldBe SonicOutput.Usage(0, 22, 0, 0)
        (parsed["out-09-userSpeechStart.json"] as SonicOutput.UserSpeechStart).inputAudioOffsetMs shouldBe 4000
        (parsed["out-20-completionEnd.json"] as SonicOutput.CompletionEnd).stopReason shouldBe "END_TURN"
        SonicEvents.parse("not json") shouldBe SonicOutput.Unknown("malformed")
    }

    test("system prompt contains no protected data and states the marker rules") {
        val p = SystemPrompt.build("kiara", Language.HI, "the Gupta household")
        p shouldContain "the Gupta household"
        p shouldContain "speak_exactly"
        p shouldContain "[[SERVER]]"
        p shouldNotContain "+91"
        Regex("\\d{6,}").containsMatchIn(p) shouldBe false
    }

    test("history keeps the last 40 utterances plus one state line") {
        val us = (1..60).map { Utterance(if (it % 2 == 0) Speaker.AGENT else Speaker.CALLER, "u$it", Language.EN, it * 10L, it * 10L + 5) } +
            Utterance(Speaker.LIVE, "takeover", null, 0, 1)
        val h = HistoryBuilder.build(us, ServerState(intent = "DELIVERY", language = Language.EN, directionsRepetitions = 2))
        h shouldHaveSize 41
        h[0].text shouldContain "intent=DELIVERY"
        h[0].text shouldContain "directions_repetitions=2"
        h[1].text shouldBe "u21"
        h.last().text shouldBe "u60"
        h.last().role shouldBe "ASSISTANT"
    }

    test("history passes through the PII guard in stalling mode") {
        val guard = PiiGuard(setOf("Sharma", "9876543210"))
        val us = listOf(Utterance(Speaker.CALLER, "I am Sharma, call 98765 43210", Language.EN, 0, 1))
        HistoryBuilder.build(us, ServerState(), guard)[1].text shouldNotContain "Sharma"
        HistoryBuilder.build(us, ServerState(), guard)[1].text shouldNotContain "43210"
        HistoryBuilder.build(us, ServerState())[1].text shouldContain "Sharma"
    }
})

private infix fun String.shouldBeJson(expected: String) {
    obj(this) shouldBe obj(expected)
}
