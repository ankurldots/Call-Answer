package com.callanswer.app.push

import androidx.datastore.core.DataStoreFactory
import com.callanswer.app.screening.RecentCallCache
import com.callanswer.app.screening.RecentCallCacheSerializer
import com.callanswer.core.model.IntentCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentCallerPushTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = 10_000_000L

    private fun cache() = RecentCallCache(
        DataStoreFactory.create(RecentCallCacheSerializer, produceFile = { File(tmp.root, "c.json") }),
        nowMs = { now },
    )

    private fun msg(endMs: Long, intent: String = "DELIVERY") = mapOf(
        "type" to "recent_caller", "number" to "+919812345678", "endMs" to endMs.toString(),
        "intent" to intent, "callId" to "c1",
    )

    @Test fun storesTheRecordWithoutNotification() = runBlocking {
        val c = cache()
        RecentCallerPush(c).handle(msg(now - 1000))
        val r = c.get("+919812345678")
        assertEquals(now - 1000, r!!.endMs)
        assertEquals(IntentCategory.DELIVERY, r.intent)
        assertEquals("c1", r.callId)
    }

    @Test fun olderRecordNeverReplacesNewer() = runBlocking {
        val c = cache()
        val p = RecentCallerPush(c)
        p.handle(msg(now - 1000, "DELIVERY"))
        p.handle(msg(now - 5000, "UNKNOWN"))
        assertEquals(now - 1000, c.get("+919812345678")!!.endMs)
    }

    @Test fun parsingIsTolerant() {
        val e = RecentCallerPush.parse(mapOf("number" to "+919812345678", "end_ms" to "5", "intent" to "door-not opened", "call_id" to "x"))
        assertEquals(IntentCategory.DOOR_NOT_OPENED, e!!.intent)
        assertEquals("x", e.callId)
        assertEquals(IntentCategory.UNKNOWN, RecentCallerPush.parse(msg(5, "weird"))!!.intent)
        assertNull(RecentCallerPush.parse(mapOf("number" to "+919812345678")))
        assertNull(RecentCallerPush.parse(mapOf("endMs" to "5")))
        assertNull(RecentCallerPush.parse(msg(5, "SOLICITATION")))
        assertNotNull(RecentCallerPush.parse(msg(5).minus("callId")))
    }
}
