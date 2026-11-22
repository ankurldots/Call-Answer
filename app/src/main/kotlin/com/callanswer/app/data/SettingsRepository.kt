package com.callanswer.app.data

import com.callanswer.app.api.ApiException
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.api.PutSettingsResult
import com.callanswer.app.api.SettingsDocument
import com.callanswer.core.model.CoreJson
import com.callanswer.core.tenant.SettingsMerge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A settings edit that has not reached the server: [edits] are the changed fields only. */
data class PendingEdit(val baseVersion: Long, val edits: JsonObject, val baseValues: JsonObject)

/** How the User settles a field that changed on both sides with different values. */
enum class ConflictChoice { KEEP_MINE, KEEP_SERVER }

/** Outcome of [SettingsRepository.sync]. The cache and the pending edit are only changed as described. */
sealed interface SyncResult {
    /** Nothing was pending; the cache now holds [document]. */
    data class UpToDate(val document: SettingsDocument) : SyncResult
    /** The edit was saved; the cache holds [document] and the pending edit is gone. */
    data class Saved(val document: SettingsDocument) : SyncResult
    /** Fields changed on both sides. The edit stays pending; call [SettingsRepository.resolve] with a choice per field. */
    data class NeedsResolution(val conflicts: List<Conflict>, val server: SettingsDocument) : SyncResult
    /** The server rejected [field] (HTTP 400). The edit stays pending. */
    data class Invalid(val field: String, val message: String) : SyncResult
    /** Every attempt ended in a 409. The edit stays pending. */
    data object RetriesExhausted : SyncResult
    /** No connection (or another API failure [error]). The cache and the edit stay as they were. */
    data class Failed(val error: ApiException) : SyncResult
}

data class Conflict(val field: String, val base: JsonElement?, val mine: JsonElement, val server: JsonElement?)

/**
 * Offline cache of the server settings document and the sync of one pending edit. The server is the source of truth:
 * every document received from it replaces the cache. Merge rules are those of [SettingsMerge.threeWay].
 */
class SettingsRepository(
    private val api: CompanionApi,
    private val cache: SettingsCacheDao,
    private val outbox: OutboxDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** The last document received from the server, or null before the first sync. */
    suspend fun cached(): SettingsDocument? = cache.get()?.let { SettingsDocument(it.version, parseObject(it.json)) }

    suspend fun pendingEdit(): PendingEdit? = outbox.firstOfKind(OutboxKinds.SETTINGS_EDIT)?.toPending()

    /** The cached document with the pending edit applied: what the settings screens show while offline. */
    suspend fun effective(): JsonObject? = lock.withLock {
        val doc = cache.get()?.let { parseObject(it.json) } ?: return@withLock pendingEdit()?.edits
        val edits = pendingEdit()?.edits ?: return@withLock doc
        JsonObject(LinkedHashMap(doc).apply { putAll(edits) })
    }

    /** Drops the pending edit (for example after the server rejected a field). The cache is not changed. */
    suspend fun discardPending() = lock.withLock { outbox.deleteKind(OutboxKinds.SETTINGS_EDIT); Unit }

    /**
     * Records [changed] fields as the pending edit. A later edit joins the pending one; the first base version and the
     * first base values stay, so the merge still compares with the document the User started from.
     */
    suspend fun saveEdit(changed: JsonObject): PendingEdit = lock.withLock {
        val current = cache.get()
        val serverDoc = current?.let { parseObject(it.json) } ?: JsonObject(emptyMap())
        val existing = pendingEdit()
        val edits = LinkedHashMap<String, JsonElement>(existing?.edits.orEmpty()).apply { putAll(changed) }
        val base = LinkedHashMap<String, JsonElement>(existing?.baseValues.orEmpty())
        for (field in changed.keys) if (field !in base) serverDoc[field]?.let { base[field] = it }
        val pending = PendingEdit(existing?.baseVersion ?: current?.version ?: 0L, JsonObject(edits), JsonObject(base))
        store(pending)
        pending
    }

    /**
     * Reads the server document, replaces the cache with it, and sends the pending edit when there is one: directly when
     * the versions match, after a three-way merge otherwise, and again with the document of each 409 (at most
     * [MAX_ATTEMPTS] attempts in all).
     */
    suspend fun sync(): SyncResult = lock.withLock { syncLocked() }

    /**
     * Settles the conflicts of the last [SyncResult.NeedsResolution], then syncs again. A field missing from [choices]
     * keeps the server value.
     */
    suspend fun resolve(choices: Map<String, ConflictChoice>): SyncResult = lock.withLock {
        val pending = pendingEdit() ?: return@withLock syncLocked()
        val server = try { api.getSettings() } catch (e: ApiException) { return@withLock SyncResult.Failed(e) }
        remember(server)
        val edits = LinkedHashMap<String, JsonElement>()
        val base = LinkedHashMap<String, JsonElement>()
        for ((field, mine) in pending.edits) {
            val conflicted = pending.baseValues[field] != server.settings[field] && server.settings[field] != mine
            val choice = choices[field] ?: ConflictChoice.KEEP_SERVER
            if (conflicted && choice == ConflictChoice.KEEP_SERVER) continue
            edits[field] = mine
            // From here the field counts as unchanged on the server, so the merge takes the local value.
            if (conflicted) server.settings[field]?.let { base[field] = it } else pending.baseValues[field]?.let { base[field] = it }
        }
        if (edits.isEmpty()) {
            outbox.deleteKind(OutboxKinds.SETTINGS_EDIT)
            return@withLock SyncResult.UpToDate(server)
        }
        store(PendingEdit(server.version, JsonObject(edits), JsonObject(base)))
        syncLocked()
    }

    private suspend fun syncLocked(): SyncResult {
        var server = try { api.getSettings() } catch (e: ApiException) { return SyncResult.Failed(e) }
        remember(server)
        val pending = pendingEdit() ?: return SyncResult.UpToDate(server)
        repeat(MAX_ATTEMPTS) {
            val candidate: JsonObject
            if (server.version == pending.baseVersion) {
                candidate = overlay(server.settings, pending.edits)
            } else {
                val merge = SettingsMerge.threeWay(pending.baseValues, server.settings, pending.edits)
                if (merge.conflicts.isNotEmpty()) {
                    return SyncResult.NeedsResolution(
                        merge.conflicts.map { Conflict(it, pending.baseValues[it], pending.edits.getValue(it), server.settings[it]) },
                        server,
                    )
                }
                candidate = merge.merged
            }
            val result = try { api.putSettings(server.version, candidate) } catch (e: ApiException) { return SyncResult.Failed(e) }
            when (result) {
                is PutSettingsResult.Saved -> {
                    remember(result.document)
                    outbox.deleteKind(OutboxKinds.SETTINGS_EDIT)
                    return SyncResult.Saved(result.document)
                }
                is PutSettingsResult.Invalid -> return SyncResult.Invalid(result.field, result.message)
                is PutSettingsResult.Conflict -> { server = result.current; remember(server) }
            }
        }
        return SyncResult.RetriesExhausted
    }

    private suspend fun remember(doc: SettingsDocument) =
        cache.put(SettingsCacheEntity(version = doc.version, json = doc.settings.toString(), updatedAtEpochMs = nowMs()))

    private suspend fun store(p: PendingEdit) {
        outbox.replaceKind(
            OutboxEntity(
                kind = OutboxKinds.SETTINGS_EDIT, payload = "", createdAtEpochMs = nowMs(),
                baseVersion = p.baseVersion, changedFields = p.edits.toString(), baseValues = p.baseValues.toString(),
            ),
        )
    }

    private fun overlay(server: JsonObject, edits: JsonObject) = JsonObject(LinkedHashMap(server).apply { putAll(edits) })

    private fun OutboxEntity.toPending() = PendingEdit(
        baseVersion ?: 0L,
        parseObject(changedFields ?: "{}"),
        parseObject(baseValues ?: "{}"),
    )

    private fun parseObject(s: String): JsonObject = CoreJson.parseToJsonElement(s) as JsonObject

    companion object { const val MAX_ATTEMPTS = 3 }
}
