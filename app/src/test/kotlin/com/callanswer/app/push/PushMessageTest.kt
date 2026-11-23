package com.callanswer.app.push

import com.callanswer.app.api.ApiConfig
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.api.TokenSource
import com.callanswer.app.api.alertAck
import com.callanswer.app.api.registerDevice
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushMessageTest {
    @Test fun parsesTheMessageTypes() {
        assertEquals(PushMessage.TakeoverAlert("c1", "+911"), PushMessageParser.parse(mapOf("type" to "TAKEOVER_ALERT", "callId" to "c1", "number" to "+911")))
        assertEquals(PushMessage.CallStarted("c1", null), PushMessageParser.parse(mapOf("type" to "call_started", "call_id" to "c1")))
        assertEquals(PushMessage.CallEnded("c1"), PushMessageParser.parse(mapOf("type" to "CALL_ENDED", "callId" to "c1")))
        assertEquals(
            PushMessage.SendMapSms("c1", "+912", "Gate 2"),
            PushMessageParser.parse(mapOf("type" to "send_map_sms", "callId" to "c1", "number" to "+912", "text" to "Gate 2")),
        )
        assertTrue(PushMessageParser.parse(mapOf("type" to "recent_caller", "number" to "+913")) is PushMessage.RecentCaller)
    }

    @Test fun ignoresUnknownTypesAndIncompleteMessages() {
        assertTrue(PushMessageParser.parse(mapOf("type" to "COST_80")) is PushMessage.Ignored)
        assertTrue(PushMessageParser.parse(mapOf("type" to "TAKEOVER_ALERT")) is PushMessage.Ignored)
        assertTrue(PushMessageParser.parse(mapOf("type" to "send_map_sms", "callId" to "c1", "number" to "+912")) is PushMessage.Ignored)
        assertTrue(PushMessageParser.parse(emptyMap()) is PushMessage.Ignored)
    }

    private class Notifier : PushNotifier {
        val events = mutableListOf<String>()
        override fun showTakeoverAlert(callId: String, number: String?) { events += "alert:$callId" }
        override fun showCallStarted(callId: String, number: String?) { events += "started:$callId" }
        override fun clearCall(callId: String) { events += "clear:$callId" }
    }

    @Test fun takeoverAlertIsShownThenAcknowledgedAndMapSmsIsRouted() = runBlocking {
        val n = Notifier()
        val acks = mutableListOf<String>()
        val sms = mutableListOf<Triple<String, String, String>>()
        val h = PushHandler(n, alertAck = { acks += it }, sendMapSms = { c, num, t -> sms += Triple(c, num, t) })
        h.handle(PushMessage.TakeoverAlert("c1", null))
        h.handle(PushMessage.CallEnded("c1"))
        h.handle(PushMessage.SendMapSms("c2", "+91", "hi"))
        assertEquals(listOf("alert:c1", "clear:c1"), n.events)
        assertEquals(listOf("c1"), acks)
        assertEquals(listOf(Triple("c2", "+91", "hi")), sms)
    }

    @Test fun failedAckIsRetriedOnceAndNeverThrows() = runBlocking {
        var calls = 0
        val h = PushHandler(Notifier(), alertAck = { calls++; throw ApiException.Server(500) }, sendMapSms = { _, _, _ -> })
        h.handle(PushMessage.TakeoverAlert("c1", null))
        assertEquals(2, calls)
    }

    @Test fun deviceAndAckRequests() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val api = CompanionApi(ApiConfig(server.url("/api/v1").toString().trimEnd('/')), TokenSource { "t" })
            server.enqueue(MockResponse().setResponseCode(204))
            api.registerDevice("dev 1", "tok")
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals("/api/v1/devices/dev%201", put.path)
            assertEquals("{\"fcmToken\":\"tok\"}", put.body.readUtf8())
            server.enqueue(MockResponse().setResponseCode(204))
            api.alertAck("c1")
            val ack = server.takeRequest()
            assertEquals("POST", ack.method)
            assertEquals("/api/v1/calls/c1/alert-ack", ack.path)
        } finally {
            server.shutdown()
        }
    }
}
