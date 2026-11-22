package com.callanswer.agent.sonic

import com.callanswer.core.model.Speaker
import com.callanswer.core.model.Utterance
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as strContain
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.CopyOnWriteArrayList

private class RenewFake : SonicStreamFactory {
    val streams = CopyOnWriteArrayList<Stream>()
    class Stream(val listener: SonicStreamListener) : SonicStream {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        override fun send(event: String) { sent.add(event) }
        override fun close() { closed = true }
        fun audioCount() = sent.count { it.contains("\"audioInput\"") && !it.contains("AAAAAA") }
    }
    override fun open(listener: SonicStreamListener): SonicStream = Stream(listener).also { streams.add(it) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRenewerTest : FunSpec({
    val runner = SonicToolRunner { _, _ -> buildJsonObject { put("status", JsonPrimitive("OK")) } }
    val cfg = RenewalConfig(maxSessionSeconds = 10, idleSeconds = 1000, maxSwitchWaitMs = 300, finalTextWaitMs = 200, pollMs = 10)

    test("renewal time is 80 percent of the limit and below the hard cut") {
        RenewalConfig().renewAfterMs shouldBe 384_000L
        RenewalConfig(maxSessionSeconds = 480).renewAfterMs shouldBe 384_000L
    }

    test("replacement opens at 80 percent with history, switches after 3 s when busy, replays ring once, closes old") {
        runTest {
            val f = RenewFake()
            var n = 0
            val transcript = mutableListOf(Utterance(Speaker.CALLER, "hello", null, 0, 1))
            val notices = mutableListOf<RenewNotice>()
            val r = SessionRenewer(
                backgroundScope, cfg.copy(maxSwitchWaitMs = 3000), "kiara", "sys",
                newSession = { SonicSession(SessionIds("p${n++}", "a$n"), f, runner, backgroundScope) },
                transcript = { transcript.toList() }, serverState = { ServerState(intent = "DELIVERY") },
                isQuiet = { false }, clockMs = { testScheduler.currentTime }, onNotice = { notices.add(it) },
            )
            r.start() shouldBe true
            r.sendAudio(ByteArray(48_000) { 1 })          // 1.5 s: only the last 1 s is kept
            delay(7_900); f.streams.size shouldBe 1
            delay(200); f.streams.size shouldBe 2           // requested at 8 s = 80 percent
            f.streams[1].sent.any { it.contains("hello") } shouldBe true
            f.streams[1].sent.any { it.contains("DELIVERY") } shouldBe true
            r.current()!!.ids.promptName shouldBe "p0"       // not quiet: still the old session
            r.sendAudio(ByteArray(320) { 2 })
            f.streams[1].audioCount() shouldBe 0
            delay(3_100)
            notices.filterIsInstance<RenewNotice.Renewed>().size shouldBe 1
            (notices[0] as RenewNotice.Renewed).replayedBytes shouldBe 32_000
            r.current()!!.ids.promptName shouldBe "p1"
            f.streams[0].closed shouldBe true
            f.streams[0].sent.last() strContain "sessionEnd"
            val before = f.streams[0].audioCount()
            r.sendAudio(ByteArray(320) { 3 })
            f.streams[0].audioCount() shouldBe before      // two sessions never both get audio
            r.end()
        }
    }

    test("history is built after the late FINAL text of the Agent after the late FINAL text") {
        runTest {
            val f = RenewFake()
            var n = 0
            val transcript = mutableListOf<Utterance>()
            val notices = mutableListOf<RenewNotice>()
            val r = SessionRenewer(
                backgroundScope, cfg, "kiara", "sys",
                newSession = { SonicSession(SessionIds("p${n++}", "a$n"), f, runner, backgroundScope) },
                transcript = { transcript.toList() }, serverState = { ServerState() },
                clockMs = { testScheduler.currentTime },
                onEvent = { if (it is SonicEvent.AgentTranscript) transcript.add(Utterance(Speaker.AGENT, it.text, null, 0, 1)) },
                onNotice = { notices.add(it) },
            )
            r.start() shouldBe true
            // Agent audio arrives; FINAL text comes late, after the renewal was requested.
            f.streams[0].listener.onEvent("""{"event":{"audioOutput":{"contentId":"c1","content":"AAAA"}}}""")
            delay(7_990)
            launch {
                delay(150)
                val l = f.streams[0].listener
                l.onEvent("""{"event":{"contentStart":{"contentId":"c2","type":"TEXT","role":"ASSISTANT","additionalModelFields":"{\"generationStage\":\"FINAL\"}"}}}""")
                l.onEvent("""{"event":{"textOutput":{"contentId":"c2","role":"ASSISTANT","content":"late final words"}}}""")
            }
            delay(300)
            f.streams.size shouldBe 2
            f.streams[1].sent.any { it.contains("late final words") } shouldBe true
            notices.filterIsInstance<RenewNotice.Renewed>().size shouldBe 1

            r.end()
        }
    }

    test("abrupt stream end after output triggers the same procedure") {
        runTest {
            val f = RenewFake()
            var n = 0
            val notices = mutableListOf<RenewNotice>()
            val r = SessionRenewer(
                backgroundScope, cfg, "kiara", "sys",
                newSession = { SonicSession(SessionIds("p${n++}", "a$n"), f, runner, backgroundScope) },
                transcript = { listOf(Utterance(Speaker.CALLER, "before the cut", null, 0, 1)) }, serverState = { ServerState() },
                clockMs = { testScheduler.currentTime }, onNotice = { notices.add(it) },
            )
            r.start() shouldBe true
            f.streams[0].listener.onEvent("""{"event":{"completionStart":{}}}""")
            f.streams[0].listener.onError(object : RuntimeException("ModelTimeoutException") {})
            delay(100)
            f.streams.size shouldBe 2
            f.streams[1].sent.any { it.contains("before the cut") } shouldBe true
            notices.filterIsInstance<RenewNotice.Renewed>().map { it.reason } shouldContain RenewReason.STREAM_END
            r.current()!!.ids.promptName shouldBe "p1"
            r.end()
        }
    }

    test("idle limit renews the session") {
        runTest {
            val f = RenewFake()
            var n = 0
            val notices = mutableListOf<RenewNotice>()
            val r = SessionRenewer(
                backgroundScope, cfg.copy(idleSeconds = 2), "kiara", "sys",
                newSession = { SonicSession(SessionIds("p${n++}", "a$n"), f, runner, backgroundScope) },
                transcript = { emptyList() }, serverState = { ServerState() },
                clockMs = { testScheduler.currentTime }, onNotice = { notices.add(it) },
            )
            r.start() shouldBe true
            delay(2_500)
            notices.filterIsInstance<RenewNotice.Renewed>().first().reason shouldBe RenewReason.IDLE
            r.end()
        }
    }
})

