package com.callanswer.app.data

import com.callanswer.app.api.ApiConfig
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.api.TokenSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Fake companion server with the versioned settings contract: 409 on a stale expectedVersion, the stored document back. */
private class FakeSettingsServer : Dispatcher() {
    var version = 0L
    var settings: JsonObject = JsonObject(emptyMap())
    var online = true
    var beforePut: (() -> Unit)? = null
    var puts = 0
    override fun dispatch(request: RecordedRequest): MockResponse {
        if (!online) return MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START)
        if (request.method == "GET") return doc(200)
        puts++
        beforePut?.also { beforePut = null }?.invoke()
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val expected = body.getValue("expectedVersion").jsonPrimitive.content.toLong()
        val candidate = body.getValue("settings").jsonObject
        if (candidate["userMobile"]?.jsonPrimitive?.content == "bad") {
            return MockResponse().setResponseCode(400).setBody("""{"field":"userMobile","message":"invalid"}""")
        }
        if (expected != version) {
            return MockResponse().setResponseCode(409).setBody("""{"currentVersion":$version,"settings":$settings}""")
        }
        version++; settings = candidate
        return doc(200)
    }
    private fun doc(code: Int) = MockResponse().setResponseCode(code).setBody("""{"version":$version,"settings":$settings}""")
}

private class FakeCacheDao : SettingsCacheDao {
    var row: SettingsCacheEntity? = null
    override suspend fun get() = row
    override suspend fun put(row: SettingsCacheEntity) { this.row = row }
    override suspend fun clear(): Int { row = null; return 1 }
}

private class FakeOutboxDao : OutboxDao() {
    val rows = mutableListOf<OutboxEntity>()
    private var next = 1L
    override suspend fun enqueue(item: OutboxEntity): Long { rows += item.copy(id = next); return next++ }
    override suspend fun pending() = rows.toList()
    override suspend fun count() = rows.size
    override suspend fun recordAttempt(id: Long) = 1
    override suspend fun delete(id: Long) = if (rows.removeAll { it.id == id }) 1 else 0
    override suspend fun firstOfKind(kind: String) = rows.firstOrNull { it.kind == kind }
    override suspend fun deleteKind(kind: String) = rows.count { it.kind == kind }.also { rows.removeAll { r -> r.kind == kind } }
    override suspend fun replaceKind(item: OutboxEntity): Long { deleteKind(item.kind); return enqueue(item) }
}

class SettingsRepositoryTest {
    private lateinit var mock: MockWebServer
    private val fake = FakeSettingsServer()
    private val cache = FakeCacheDao()
    private val outbox = FakeOutboxDao()
    private lateinit var repo: SettingsRepository

    private fun obj(vararg p: Pair<String, String>) = buildJsonObject { p.forEach { put(it.first, it.second) } }

    @Before fun setUp() {
        mock = MockWebServer().apply { dispatcher = fake; start() }
        val api = CompanionApi(ApiConfig(mock.url("/api/v1").toString().trimEnd('/')), TokenSource { "t" })
        repo = SettingsRepository(api, cache, outbox, nowMs = { 1L })
    }

    @After fun tearDown() { mock.shutdown() }

    @Test fun syncWithNothingPendingReplacesTheCache() = runBlocking {
        fake.version = 4; fake.settings = obj("agentName" to "A")
        repo.sync()
        assertEquals(4L, repo.cached()!!.version)
        assertEquals(JsonPrimitive("A"), repo.cached()!!.settings["agentName"])
    }

    @Test fun offlineEditStaysPendingWithBaseVersionAndFieldsThenSendsOnReconnect() = runBlocking {
        fake.version = 2; fake.settings = obj("agentName" to "A", "voiceId" to "kiara")
        repo.sync()
        fake.online = false
        repo.saveEdit(obj("agentName" to "B"))
        assertTrue(repo.sync() is SyncResult.Failed)
        val row = outbox.rows.single()
        assertEquals(2L, row.baseVersion)
        assertEquals("""{"agentName":"B"}""", row.changedFields)
        assertEquals(JsonPrimitive("B"), repo.effective()!!["agentName"])
        assertEquals("A", (repo.cached()!!.settings["agentName"] as JsonPrimitive).content)
        fake.online = true
        val saved = repo.sync() as SyncResult.Saved
        assertEquals(3L, saved.document.version)
        assertEquals(JsonPrimitive("kiara"), fake.settings["voiceId"])
        assertEquals(JsonPrimitive("B"), fake.settings["agentName"])
        assertTrue(outbox.rows.isEmpty())
        assertEquals(3L, repo.cached()!!.version)
    }

    @Test fun serverChangeToAnotherFieldIsMergedWithoutAsking() = runBlocking {
        fake.version = 1; fake.settings = obj("agentName" to "A", "voiceId" to "kiara")
        repo.sync()
        repo.saveEdit(obj("agentName" to "B"))
        fake.version = 2; fake.settings = obj("agentName" to "A", "voiceId" to "arjun")
        val saved = repo.sync() as SyncResult.Saved
        assertEquals(3L, saved.document.version)
        assertEquals(obj("agentName" to "B", "voiceId" to "arjun"), fake.settings)
    }

    @Test fun fieldChangedOnBothSidesNeedsAChoiceAndKeepMineWins() = runBlocking {
        fake.version = 1; fake.settings = obj("agentName" to "A")
        repo.sync()
        repo.saveEdit(obj("agentName" to "Mine"))
        fake.version = 2; fake.settings = obj("agentName" to "Theirs")
        val r = repo.sync() as SyncResult.NeedsResolution
        assertEquals(listOf("agentName"), r.conflicts.map { it.field })
        assertEquals(0, fake.puts)
        assertEquals(2L, repo.cached()!!.version)
        val saved = repo.resolve(mapOf("agentName" to ConflictChoice.KEEP_MINE)) as SyncResult.Saved
        assertEquals(JsonPrimitive("Mine"), saved.document.settings["agentName"])
    }

    @Test fun keepServerDropsTheLocalEdit() = runBlocking {
        fake.version = 1; fake.settings = obj("agentName" to "A")
        repo.sync()
        repo.saveEdit(obj("agentName" to "Mine"))
        fake.version = 2; fake.settings = obj("agentName" to "Theirs")
        repo.sync()
        val r = repo.resolve(mapOf("agentName" to ConflictChoice.KEEP_SERVER))
        assertTrue(r is SyncResult.UpToDate)
        assertNull(repo.pendingEdit())
        assertEquals(0, fake.puts)
    }

    @Test fun a409DuringPutIsRetriedWithTheReturnedDocument() = runBlocking {
        fake.version = 1; fake.settings = obj("agentName" to "A", "voiceId" to "kiara")
        repo.sync()
        repo.saveEdit(obj("agentName" to "B"))
        fake.beforePut = { fake.version = 2; fake.settings = obj("agentName" to "A", "voiceId" to "arjun") }
        val saved = repo.sync() as SyncResult.Saved
        assertEquals(2, fake.puts)
        assertEquals(3L, saved.document.version)
        assertEquals(obj("agentName" to "B", "voiceId" to "arjun"), fake.settings)
    }

    @Test fun retriesStopAfterThreeAttempts() = runBlocking {
        fake.version = 1; fake.settings = obj("agentName" to "A")
        repo.sync()
        repo.saveEdit(obj("voiceId" to "kiara"))
        // Every PUT finds a newer stored version.
        val racing = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "PUT") { fake.puts++; fake.version++; return MockResponse().setResponseCode(409).setBody("""{"currentVersion":${fake.version},"settings":${fake.settings}}""") }
                return MockResponse().setBody("""{"version":${fake.version},"settings":${fake.settings}}""")
            }
        }
        mock.dispatcher = racing
        assertEquals(SyncResult.RetriesExhausted, repo.sync())
        assertEquals(3, fake.puts)
        assertTrue(repo.pendingEdit() != null)
    }

    @Test fun invalidFieldKeepsThePendingEdit() = runBlocking {
        fake.version = 1; fake.settings = obj("agentName" to "A")
        repo.sync()
        repo.saveEdit(obj("userMobile" to "bad"))
        assertEquals("userMobile", (repo.sync() as SyncResult.Invalid).field)
        assertTrue(repo.pendingEdit() != null)
    }

    @Test fun secondEditKeepsTheFirstBaseVersion() = runBlocking {
        fake.version = 1; fake.settings = obj("agentName" to "A", "voiceId" to "kiara")
        repo.sync()
        repo.saveEdit(obj("agentName" to "B"))
        fake.version = 2; fake.settings = obj("agentName" to "A", "voiceId" to "arjun")
        repo.sync() // merged and saved, so this edit starts fresh
        repo.saveEdit(obj("voiceId" to "kiara"))
        val p = repo.saveEdit(obj("agentName" to "C"))
        assertEquals(3L, p.baseVersion)
        assertEquals(1, outbox.rows.size)
        assertEquals(setOf("voiceId", "agentName"), p.edits.keys)
    }
}
