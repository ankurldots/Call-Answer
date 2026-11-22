package com.callanswer.app.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CompanionApiTest {
    private lateinit var server: MockWebServer
    private val refreshes = mutableListOf<Boolean>()
    private lateinit var api: CompanionApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val tokens = TokenSource { force -> refreshes += force; if (force) "token-2" else "token-1" }
        api = CompanionApi(ApiConfig(server.url("/api/v1").toString().trimEnd('/')), tokens)
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)
        .setHeader("Content-Type", "application/json")

    @Test fun getSettingsSendsBearerTokenAndParsesDocument() = runBlocking {
        server.enqueue(json(200, """{"version":3,"settings":{"agentName":"Asha"}}"""))
        val doc = api.getSettings()
        val req = server.takeRequest()
        assertEquals("/api/v1/settings", req.path)
        assertEquals("Bearer token-1", req.getHeader("Authorization"))
        assertEquals(3L, doc.version)
        assertEquals(JsonPrimitive("Asha"), doc.settings["agentName"])
    }

    @Test fun a401RefreshesTheTokenOnceAndRepeatsTheCall() = runBlocking {
        server.enqueue(json(401, "{}"))
        server.enqueue(json(200, """{"version":0,"settings":{}}"""))
        api.getSettings()
        assertEquals(listOf(false, true), refreshes)
        server.takeRequest()
        assertEquals("Bearer token-2", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun aSecond401IsUnauthorized() = runBlocking {
        server.enqueue(json(401, "{}")); server.enqueue(json(401, "{}"))
        try { api.getSettings(); fail() } catch (e: ApiException.Unauthorized) { /* expected */ }
    }

    @Test fun putSettingsMapsSavedConflictAndInvalid() = runBlocking {
        val settings: JsonObject = buildJsonObject { put("agentName", "Meera") }
        server.enqueue(json(200, """{"version":2,"settings":{"agentName":"Meera"}}"""))
        server.enqueue(json(409, """{"currentVersion":5,"settings":{"agentName":"Other"}}"""))
        server.enqueue(json(400, """{"field":"userMobile","message":"bad"}"""))
        assertEquals(2L, (api.putSettings(1, settings) as PutSettingsResult.Saved).document.version)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"expectedVersion\":1") && body.contains("\"agentName\":\"Meera\""))
        assertEquals(5L, (api.putSettings(1, settings) as PutSettingsResult.Conflict).current.version)
        assertEquals("userMobile", (api.putSettings(1, settings) as PutSettingsResult.Invalid).field)
    }

    @Test fun statusCodesMapToExceptions() = runBlocking {
        server.enqueue(json(429, "{}").setHeader("Retry-After", "12"))
        server.enqueue(json(403, "{}")); server.enqueue(json(404, "{}")); server.enqueue(json(503, "{}"))
        try { api.me(); fail() } catch (e: ApiException.RateLimited) { assertEquals(12L, e.retryAfterSec) }
        try { api.me(); fail() } catch (e: ApiException.Forbidden) { }
        try { api.getCall("x"); fail() } catch (e: ApiException.NotFound) { }
        try { api.me(); fail() } catch (e: ApiException.Server) { assertEquals(503, e.status) }
    }

    @Test fun unreachableServerIsNetworkError() = runBlocking {
        server.shutdown()
        try { api.me(); fail() } catch (e: ApiException.Network) { }
    }

    @Test fun listCallsParsesPageAndSendsCursor() = runBlocking {
        server.enqueue(json(200, """{"calls":[],"nextCursor":"abc"}"""))
        val page = api.listCalls("c1", 10)
        assertEquals("abc", page.nextCursor)
        assertEquals("/api/v1/calls?limit=10&cursor=c1", server.takeRequest().path)
    }
}
