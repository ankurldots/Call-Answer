package com.callanswer.app.api

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LiveSocketTest {
    @Test fun tokenGoesInTheFirstMessageNotTheUrlAndEventsAreParsed() = runBlocking {
        val server = MockWebServer().apply { start() }
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
                webSocket.send("""{"type":"call_started","callId":"c1"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
        }))
        val live = LiveSocket(ApiConfig(server.url("/api/v1").toString().trimEnd('/')), TokenSource { "jwt-abc" })
        val event = withTimeout(10_000) { live.events().first() }
        assertEquals("call_started", event.type)
        assertEquals("""{"type":"auth","token":"jwt-abc"}""", received.poll(5, TimeUnit.SECONDS))
        assertFalse(server.takeRequest().path!!.contains("jwt-abc"))
        server.shutdown()
    }
}
