package com.callanswer.app.api

import com.callanswer.app.sms.MapSmsResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MapSmsResultApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CompanionApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = CompanionApi(ApiConfig(server.url("/api/v1").toString().trimEnd('/')), TokenSource { "t" })
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun postsTheResultToTheMapSmsResultEndpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        api.mapSmsResult("call 1", MapSmsResult.NO_PERMISSION)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/calls/call%201/map-sms-result", req.path)
        assertEquals("{\"status\":\"NO_PERMISSION\"}", req.body.readUtf8())
    }
}
