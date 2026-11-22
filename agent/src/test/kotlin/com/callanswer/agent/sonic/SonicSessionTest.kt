package com.callanswer.agent.sonic

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as strContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.CopyOnWriteArrayList

private class FakeStream : SonicStreamFactory, SonicStream {
    val sent = CopyOnWriteArrayList<String>()
    lateinit var listener: SonicStreamListener
    @Volatile var closed = false
    override fun open(listener: SonicStreamListener): SonicStream { this.listener = listener; return this }
    override fun send(event: String) { sent.add(event) }
    override fun close() { closed = true }
    fun names() = sent.map { Regex("""\{"event":\{"(\w+)"""").find(it)!!.groupValues[1] }
}

private fun out(name: String, body: String) = """{"event":{"$name":$body}}"""

class SonicSessionTest : FunSpec({
    val ids = SessionIds("p1", "a1")

    fun session(f: SonicStreamFactory, scope: CoroutineScope, timeoutMs: Long = 2000, runner: SonicToolRunner = SonicToolRunner { _, _ -> buildJsonObject { put("status", JsonPrimitive("OK")) } }) =
        SonicSession(ids, f, runner, scope, toolTimeoutMs = timeoutMs, silenceAfterMs = 50)

    test("start sends the documented sequence and keeps sending silence when idle") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = FakeStream()
        val s = session(f, scope)
        s.start("kiara", "system").shouldBe(true)
        runBlocking { delay(300) }
        f.names().take(5) shouldBe listOf("sessionStart", "promptStart", "contentStart", "textInput", "contentEnd")
        f.names()[5] shouldBe "contentStart"
        (f.names().count { it == "audioInput" } > 2) shouldBe true
        s.end()
        f.names().takeLast(3) shouldBe listOf("contentEnd", "promptEnd", "sessionEnd")
        f.closed shouldBe true
        scope.cancel()
    }

    test("instructions are sent as marked USER text") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = FakeStream()
        val s = session(f, scope)
        s.start("kiara", "system")
        f.sent.clear()
        s.sayExactly("Hello")
        f.sent[1] shouldBe SonicEvents.textInput("p1", Regex(""""contentName":"([^"]+)"""").find(f.sent[0])!!.groupValues[1], "[[SERVER]] Say exactly: \"Hello\"")
        s.close(); scope.cancel()
    }

    test("output events are mapped; INTERRUPTED comes from the assistant text block") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = FakeStream()
        val s = session(f, scope)
        s.start("kiara", "system")
        val l = f.listener
        l.onEvent(out("contentStart", """{"contentId":"c1","type":"TEXT","role":"USER"}"""))
        l.onEvent(out("textOutput", """{"contentId":"c1","role":"USER","content":"hi"}"""))
        l.onEvent(out("contentStart", """{"contentId":"c2","type":"TEXT","role":"ASSISTANT","additionalModelFields":"{\"generationStage\":\"SPECULATIVE\"}"}"""))
        l.onEvent(out("textOutput", """{"contentId":"c2","role":"ASSISTANT","content":"maybe"}"""))
        l.onEvent(out("contentStart", """{"contentId":"c3","type":"AUDIO","role":"ASSISTANT"}"""))
        l.onEvent(out("audioOutput", """{"contentId":"c3","content":"AAAA"}"""))
        l.onEvent(out("contentEnd", """{"contentId":"c2","type":"TEXT","stopReason":"INTERRUPTED"}"""))
        l.onEvent(out("textOutput", """{"contentId":"c2","role":"ASSISTANT","content":"{ \"interrupted\" : true }"}"""))
        l.onEvent(out("completionEnd", """{"completionId":"x","stopReason":"END_TURN"}"""))
        s.close()
        val got = runBlocking { withTimeout(2000) { s.events.toList() } }
        got[0] shouldBe SonicEvent.CallerTranscript("hi")
        got[1] shouldBe SonicEvent.SpeculativeText("maybe")
        got[2].shouldBeInstanceOf<SonicEvent.AgentAudio>().pcm.size shouldBe 3
        got shouldContain SonicEvent.Interrupted
        got shouldContain SonicEvent.Completed("END_TURN")
        got.size shouldBe 5
        scope.cancel()
    }

    test("a tool use gets exactly one toolResult, with the right ids") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = FakeStream()
        val s = session(f, scope)
        s.start("kiara", "system")
        f.sent.clear()
        val use = out("toolUse", """{"contentId":"t1","toolName":"log_note","toolUseId":"u1","content":"{\"kind\":\"INTENT\"}"}""")
        f.listener.onEvent(use)
        f.listener.onEvent(use)
        f.listener.onEvent(out("contentEnd", """{"contentId":"t1","type":"TOOL","stopReason":"TOOL_USE"}"""))
        val called = runBlocking { withTimeout(2000) { s.events.first { it is SonicEvent.ToolCalled } } } as SonicEvent.ToolCalled
        called.timedOut shouldBe false
        runBlocking { delay(100) }
        f.names().filter { it == "toolResult" }.size shouldBe 1
        f.sent.first { it.contains("toolResultInputConfiguration") } strContain "\"toolUseId\":\"u1\""
        s.close(); scope.cancel()
    }

    test("a slow tool times out and still gets one toolResult with TIMEOUT") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = FakeStream()
        val s = session(f, scope, timeoutMs = 50, runner = { _, _ -> delay(5000); JsonObject(emptyMap()) })
        s.start("kiara", "system")
        f.sent.clear()
        f.listener.onEvent(out("toolUse", """{"contentId":"t1","toolName":"get_gate_info","toolUseId":"u1","content":"{}"}"""))
        f.listener.onEvent(out("contentEnd", """{"contentId":"t1","type":"TOOL","stopReason":"TOOL_USE"}"""))
        val called = runBlocking { withTimeout(2000) { s.events.first { it is SonicEvent.ToolCalled } } } as SonicEvent.ToolCalled
        called.timedOut shouldBe true
        f.sent.first { it.contains("toolResult\"") && it.contains("content") } strContain "TIMEOUT"
        f.names().count { it == "toolResult" } shouldBe 1
        s.close(); scope.cancel()
    }

    test("a failing tool still gets one toolResult") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val f = FakeStream()
        val s = session(f, scope, runner = { _, _ -> error("boom") })
        s.start("kiara", "system")
        f.sent.clear()
        f.listener.onEvent(out("toolUse", """{"contentId":"t1","toolName":"x","toolUseId":"u1","content":"{}"}"""))
        f.listener.onEvent(out("contentEnd", """{"contentId":"t1","type":"TOOL","stopReason":"TOOL_USE"}"""))
        runBlocking { withTimeout(2000) { s.events.first { it is SonicEvent.ToolCalled } } }
        f.names().count { it == "toolResult" } shouldBe 1
        s.close(); scope.cancel()
    }

    test("start failure and stream errors are reported as events") {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bad = session({ throw IllegalStateException("no access") }, scope)
        bad.start("kiara", "system") shouldBe false
        val e = runBlocking { withTimeout(2000) { bad.events.toList() } }
        e.single().shouldBeInstanceOf<SonicEvent.StartFailed>()

        class ModelTimeoutException(m: String) : RuntimeException(m)
        val f = FakeStream()
        val s = session(f, scope)
        s.start("kiara", "system")
        f.listener.onEvent(out("completionStart", """{"completionId":"x"}"""))
        f.listener.onError(ModelTimeoutException("8 min"))
        val got = runBlocking { withTimeout(2000) { s.events.toList() } }
        got.single().shouldBeInstanceOf<SonicEvent.StreamError>().modelTimeout shouldBe true
        f.closed shouldBe true
        scope.cancel()
    }
})
