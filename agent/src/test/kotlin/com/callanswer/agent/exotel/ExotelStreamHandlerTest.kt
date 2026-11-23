package com.callanswer.agent.exotel

import com.callanswer.agent.audio.PcmCodec
import com.callanswer.agent.sonic.SonicEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

class ExotelStreamHandlerTest : FunSpec({
    test("connected, start, media, mark, stop gives one end() with CALLER_HUNG_UP") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(ExotelEvent.Connected)
                sendEvent(startEvent())
                repeat(3) { sendEvent(media()) }
                sendEvent(ExotelEvent.Mark("s1", "m1"))
                sendEvent(ExotelEvent.Stop("s1", "CA1", "callended"))
            }
            waitUntil { calls.call.ends.isNotEmpty() } shouldBe true
            calls.call.ends shouldBe listOf(StreamEnd.CALLER_HUNG_UP)
            calls.call.marks shouldBe listOf("m1")
            calls.call.audio.shouldNotBeEmpty()
            calls.call.audio.all { it.size % 2 == 0 } shouldBe true
        }
    }

    test("stop with reason stopped, and a dropped socket, end as AUDIO_INTERRUPTED once") {
        for (script in listOf<suspend io.ktor.client.plugins.websocket.DefaultClientWebSocketSession.() -> Unit>(
            { sendEvent(startEvent()); sendEvent(ExotelEvent.Stop("s1", "CA1", "stopped")) },
            { sendEvent(startEvent()); sendEvent(media()) },
        )) {
            val calls = FakeCalls()
            withStreamApp(calls) { env ->
                wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) { script() }
                waitUntil { calls.call.ends.isNotEmpty() } shouldBe true
                kotlinx.coroutines.delay(100)
                calls.call.ends shouldBe listOf(StreamEnd.AUDIO_INTERRUPTED)
            }
        }
    }

    test("Agent audio leaves as media frames that are multiples of 320 bytes and at least 3200, then one mark") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(startEvent())
                calls.call.channel.send(SonicEvent.AgentAudio(ByteArray(10_000)))
                calls.call.channel.send(SonicEvent.AgentTranscript("hello"))
                val frames = collectFor(800)
                val sizes = frames.filterIsInstance<ExotelEvent.Media>().map { PcmCodec.decodeBase64Bytes(it.payload)!!.size }
                sizes.shouldNotBeEmpty()
                sizes.all { it % 320 == 0 && it >= 3200 } shouldBe true
                frames.filterIsInstance<ExotelEvent.Mark>() shouldHaveSize 1
                frames.last().shouldBeMark()
            }
        }
    }

    test("an interruption sends exactly one clear and drops the rest of the utterance") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(startEvent())
                calls.call.channel.send(SonicEvent.AgentAudio(ByteArray(120_000)))
                waitUntil { true }
                kotlinx.coroutines.delay(50)
                calls.call.channel.send(SonicEvent.UserSpeechStarted)
                calls.call.channel.send(SonicEvent.Interrupted)
                val frames = collectFor(700)
                frames.filterIsInstance<ExotelEvent.Clear>() shouldHaveSize 1
                val afterClear = frames.dropWhile { it !is ExotelEvent.Clear }.drop(1)
                afterClear.filterIsInstance<ExotelEvent.Media>() shouldHaveSize 0
            }
        }
    }

    test("stale Agent audio after UserSpeechStarted is not sent, and a new utterance after Interrupted still plays") {
        val calls = FakeCalls()
        withStreamApp(calls) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(startEvent())
                calls.call.channel.send(SonicEvent.AgentAudio(ByteArray(60_000)))
                kotlinx.coroutines.delay(50)
                calls.call.channel.send(SonicEvent.UserSpeechStarted)
                calls.call.channel.send(SonicEvent.AgentAudio(ByteArray(60_000)))
                calls.call.channel.send(SonicEvent.AgentAudio(ByteArray(60_000)))
                calls.call.channel.send(SonicEvent.Interrupted)
                calls.call.channel.send(SonicEvent.AgentAudio(ByteArray(9_600)))
                calls.call.channel.send(SonicEvent.AgentTranscript("new"))
                val frames = collectFor(700)
                frames.filterIsInstance<ExotelEvent.Clear>() shouldHaveSize 1
                val afterClear = frames.dropWhile { it !is ExotelEvent.Clear }.drop(1)
                afterClear.filterIsInstance<ExotelEvent.Media>() shouldHaveSize 1
                afterClear.filterIsInstance<ExotelEvent.Mark>() shouldHaveSize 1
            }
        }
    }
    test("no media for the media timeout ends the call as AUDIO_INTERRUPTED") {
        val calls = FakeCalls()
        withStreamApp(calls, mediaTimeoutMs = 300) { env ->
            wsClient().webSocket(env.path(env.token()), request = { header(HttpHeaders.Authorization, env.basic) }) {
                sendEvent(startEvent())
                waitUntil(3000) { calls.call.ends.isNotEmpty() } shouldBe true
            }
            calls.call.ends shouldBe listOf(StreamEnd.AUDIO_INTERRUPTED)
        }
    }
})

private fun ExotelEvent.shouldBeMark() {
    (this is ExotelEvent.Mark) shouldBe true
}
