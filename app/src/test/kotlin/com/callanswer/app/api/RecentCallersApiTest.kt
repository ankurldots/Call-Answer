package com.callanswer.app.api

import com.callanswer.core.model.IntentCategory
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RecentCallersApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CompanionApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = CompanionApi(ApiConfig(server.url("/api/v1").toString().trimEnd('/')), TokenSource { "t" })
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun getsTheCallersOfTheLastHour() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"callers":[{"number":"+919876543210","endMs":1000,"intent":"DELIVERY"}]}"""))
        val callers = api.recentCallers()
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/recent-callers?minutes=60", req.path)
        assertEquals(listOf(RecentCaller("+919876543210", 1000, IntentCategory.DELIVERY)), callers)
    }
}
