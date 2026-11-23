package com.callanswer.app.screening

import androidx.datastore.core.DataStoreFactory
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.RecentCaller
import com.callanswer.core.model.IntentCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecentCallSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache(now: Long) = RecentCallCache(
        DataStoreFactory.create(RecentCallCacheSerializer, produceFile = { File(tmp.root, "c.json") }),
        nowMs = { now },
    )

    private suspend fun RecentCallCache.numbers() = all().map { it.e164 }

    @Test fun replacesTheCacheWithTheServerList() = runBlocking {
        val c = cache(now = 100_000)
        c.replaceAll(listOf(RecentCallEntry("+911", 90_000, IntentCategory.UNKNOWN)))
        val ok = RecentCallSync({ listOf(RecentCaller("+912", 95_000, IntentCategory.DELIVERY)) }, c).sync()
        assertTrue(ok)
        assertEquals(listOf("+912"), c.numbers())
    }

    @Test fun keepsTheCacheWhenTheServerIsUnreachable() = runBlocking {
        val c = cache(now = 100_000)
        c.replaceAll(listOf(RecentCallEntry("+911", 90_000, IntentCategory.UNKNOWN)))
        val ok = RecentCallSync({ throw ApiException.Network(java.io.IOException()) }, c).sync()
        assertFalse(ok)
        assertEquals(listOf("+911"), c.numbers())
    }
}
