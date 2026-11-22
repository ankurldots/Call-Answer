package com.callanswer.app.ui.screens

import com.callanswer.app.api.ApiConfig
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.api.LiveEvent
import com.callanswer.app.api.LiveSocket
import com.callanswer.app.api.TokenSource
import com.callanswer.app.api.endCall
import com.callanswer.app.api.sendQuickReply
import com.callanswer.app.api.takeOver
import com.callanswer.app.ui.components.BubbleSpeaker
import com.callanswer.core.model.CoreJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiveCallTest {
    private lateinit var server: MockWebServer
    private val base get() = server.url("/api/v1").toString().trimEnd('/')

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun ev(json: String) = CoreJson.parseToJsonElement(json).jsonObject.let { LiveEvent(it["type"].toString().trim('"'), it) }

    @Test fun reducerBuildsTheScreenStateAndIgnoresOtherCallsAndUnknownEvents() {
        var s = LiveCallState("c1")
        s = s.reduce(ev("""{"type":"call_started","callId":"c1","callerNumber":"+919800000000","startedAtEpochMs":1000}"""), 5000)
        s = s.reduce(ev("""{"type":"intent","callId":"c1","intent":"DELIVERY"}"""), 5000)
        s = s.reduce(ev("""{"type":"language","language":"hi"}"""), 5000)
        s = s.reduce(ev("""{"type":"transcript","callId":"c1","speaker":"AGENT","text":"Hello"}"""), 5000)
        s = s.reduce(ev("""{"type":"transcript","callId":"c2","speaker":"CALLER","text":"other call"}"""), 5000)
        s = s.reduce(ev("""{"type":"transcript","callId":"c1","speaker":"caller"}"""), 5000)
        s = s.reduce(ev("""{"type":"mystery","x":1}"""), 5000)
        assertEquals("+919800000000", s.callerNumber)
        assertEquals("DELIVERY", s.intent)
        assertEquals("hi", s.language)
        assertEquals(listOf(LiveLine(BubbleSpeaker.AGENT, "Hello")), s.transcript)
        assertEquals(61, s.elapsedSec(62_000))
        assertTrue(s.canTakeOver)
        assertEquals("नमस्ते", s.quickReplyText("Hello", "नमस्ते"))
        assertEquals("Hello", s.quickReplyText("Hello", ""))
    }

    @Test fun takeoverStatusDrivesTheBridgeAndEndedDisablesTakeOver() {
        var s = LiveCallState("c1", startedAtMs = 0)
        s = s.reduce(ev("""{"type":"takeover_status","status":"connecting"}"""), 1)
        assertEquals(BridgeStatus.CONNECTING, s.bridge)
        assertFalse(s.canTakeOver)
        s = s.reduce(ev("""{"type":"takeover_status","status":"connected"}"""), 2)
        assertFalse(s.canEnd)
        s = s.reduce(ev("""{"type":"takeover_status","status":"failed"}"""), 3)
        assertEquals(BridgeStatus.FAILED, s.bridge)
        assertTrue(s.canTakeOver)
        s = s.reduce(ev("""{"type":"call_ended","callId":"c1"}"""), 9000)
        assertTrue(s.ended)
        assertFalse(s.canTakeOver)
        assertFalse(s.canEnd)
        assertEquals(9, s.elapsedSec(99_000))
    }

    @Test fun elapsedFormat() {
        assertEquals("0:05", formatElapsed(5))
        assertEquals("2:03", formatElapsed(123))
        assertEquals("1:01:01", formatElapsed(3661))
    }

    @Test fun fakeSocketEventsReachTheStateAndTheFeedStopsAtCallEnded() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"call_started","callId":"c1","callerNumber":"+911"}""")
                webSocket.send("not json")
                webSocket.send("""{"type":"transcript","callId":"c1","speaker":"CALLER","text":"Parcel"}""")
                webSocket.send("""{"type":"call_ended","callId":"c1"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
        }))
        var s = LiveCallState("c1")
        val live = LiveSocket(ApiConfig(base), TokenSource { "jwt" })
        withTimeout(10_000) { followLive(live, "c1", {}) { s = s.reduce(it, 0) } }
        assertEquals("+911", s.callerNumber)
        assertEquals(1, s.transcript.size)
        assertTrue(s.ended)
    }

    @Test fun actionsPostWithoutADestinationNumber() = runBlocking {
        val api = CompanionApi(ApiConfig(base), TokenSource { "t" })
        repeat(3) { server.enqueue(MockResponse().setResponseCode(202).setBody("{}")) }
        api.takeOver("c 1")
        api.endCall("c1")
        api.sendQuickReply("c1", "Leave it at the door.")
        val take = server.takeRequest()
        assertEquals("/api/v1/calls/c%201/takeover", take.path)
        assertEquals("POST", take.method)
        assertEquals("", take.body.readUtf8())
        assertEquals("/api/v1/calls/c1/end", server.takeRequest().path)
        val q = server.takeRequest()
        assertEquals("/api/v1/calls/c1/quick-reply", q.path)
        assertEquals("""{"text":"Leave it at the door."}""", q.body.readUtf8())
    }
}
