package com.callanswer.app.sms

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MapSmsSenderTest {
    private class Transport(val parts: Int = 1, val throws: Boolean = false, val react: (String) -> Unit = {}) : MapSmsTransport {
        override fun send(number: String, text: String, attemptId: String): Int {
            if (throws) error("refused")
            react(attemptId)
            return parts
        }
    }

    private fun sender(t: MapSmsTransport, failed: MutableList<String>, granted: Boolean = true, timeout: Long = 200) =
        MapSmsSender(null, { failed += it }, { granted }, timeout, t).also { it.registrationEnabled = false }

    @Test fun deliveredWhenEveryPartIsSentAndDelivered() = runBlocking {
        val failed = mutableListOf<String>()
        lateinit var s: MapSmsSender
        s = sender(Transport(parts = 2) { id ->
            repeat(2) { s.onResult(id, MapSmsSender.KIND_SENT, true); s.onResult(id, MapSmsSender.KIND_DELIVERED, true) }
        }, failed)
        assertEquals(MapSmsResult.DELIVERED, s.send("100", "loc"))
        assertEquals(emptyList<String>(), failed)
    }

    @Test fun failsAndNotifiesWhenNotDeliveredInTime() = runBlocking {
        val failed = mutableListOf<String>()
        lateinit var s: MapSmsSender
        s = sender(Transport { id -> s.onResult(id, MapSmsSender.KIND_SENT, true) }, failed)
        assertEquals(MapSmsResult.FAILED, s.send("100", "loc"))
        assertEquals(listOf("100"), failed)
    }

    @Test fun failsOnSentErrorPermissionAndRefusal() = runBlocking {
        val failed = mutableListOf<String>()
        lateinit var s: MapSmsSender
        s = sender(Transport { id -> s.onResult(id, MapSmsSender.KIND_SENT, false) }, failed)
        assertEquals(MapSmsResult.FAILED, s.send("1", "x"))
        assertEquals(MapSmsResult.FAILED, sender(Transport(), failed, granted = false).send("2", "x"))
        assertEquals(MapSmsResult.FAILED, sender(Transport(throws = true), failed).send("3", "x"))
        assertEquals(listOf("1", "2", "3"), failed)
    }
}
