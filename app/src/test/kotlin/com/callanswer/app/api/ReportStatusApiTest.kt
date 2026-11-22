package com.callanswer.app.api

import com.callanswer.core.model.ReportStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ReportStatusApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CompanionApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = CompanionApi(ApiConfig(server.url("/api/v1").toString().trimEnd('/')), TokenSource { "t" })
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun postsTheStatusToTheCallReportEndpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        api.reportStatus("call 1", ReportStatus.HANDED_TO_MESSAGING_APP)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/calls/call%201/report-status", req.path)
        assertEquals("{\"status\":\"HANDED_TO_MESSAGING_APP\"}", req.body.readUtf8())
    }
}
