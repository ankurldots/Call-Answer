package com.callanswer.app.api

import com.callanswer.app.ui.screens.ConsentStatus
import com.callanswer.app.ui.screens.unlocksAgentAndCodes
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConsentApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: CompanionApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = CompanionApi(ApiConfig(server.url("/api/v1").toString().trimEnd('/')), TokenSource { "t" })
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun acceptPostsTheConsentTextVersion() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        api.acceptConsent("v1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/v1/consent", req.path)
        assertTrue(req.body.readUtf8().contains("\"version\":\"v1\""))
    }

    @Test fun withdrawDeletesTheConsent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        api.withdrawConsent()
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/v1/consent", req.path)
    }

    @Test fun onlyAcceptedStatusUnlocksAgentAndCodes() {
        assertTrue(ConsentStatus.Accepted.unlocksAgentAndCodes())
        assertFalse(ConsentStatus.NotAccepted.unlocksAgentAndCodes())
        assertFalse(ConsentStatus.Loading.unlocksAgentAndCodes())
        assertFalse(ConsentStatus.Failed(0).unlocksAgentAndCodes())
    }
}
