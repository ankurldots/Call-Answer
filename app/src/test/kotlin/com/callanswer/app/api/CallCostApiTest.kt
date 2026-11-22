package com.callanswer.app.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class CallCostApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CompanionApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = CompanionApi(ApiConfig(server.url("/api/v1").toString().trimEnd('/')), TokenSource { "t" })
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)
        .setHeader("Content-Type", "application/json")

    @Test fun recordingUrlParsesPresignedUrl() = runBlocking {
        server.enqueue(json(200, """{"url":"https://s3.example/x?sig=1","expiresInSeconds":900}"""))
        val r = api.recordingUrl("call 1")
        assertEquals(900L, r.expiresInSeconds)
        assertEquals("/api/v1/calls/call%201/recording", server.takeRequest().path)
    }

    @Test fun recordingUrlWithoutRecordingIsNotFound() {
        server.enqueue(json(404, """{"error":"not_found"}"""))
        assertThrows(ApiException.NotFound::class.java) { runBlocking { api.recordingUrl("c") } }
    }

    @Test fun usageMonthParsesTotalAndNullLimit() = runBlocking {
        server.enqueue(json(200, """{"month":"2025-01","totalPaise":1250,"monthlyLimitInr":null}"""))
        val u = api.usageMonth()
        assertEquals(1250L, u.totalPaise)
        assertNull(u.monthlyLimitInr)
        assertEquals("/api/v1/usage/month", server.takeRequest().path)
    }
}
