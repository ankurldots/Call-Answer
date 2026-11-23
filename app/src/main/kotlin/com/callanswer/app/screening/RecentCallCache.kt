package com.callanswer.app.screening

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.callanswer.core.cloud.RecentCall
import com.callanswer.core.model.IntentCategory
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** One cached Recent_Call_Record, keyed by the normalized Caller number. */
@Serializable
data class RecentCallEntry(
    val e164: String,
    val endMs: Long,
    val intent: IntentCategory,
    val callId: String? = null,
) {
    fun toRecentCall() = RecentCall(endMs = endMs, intent = intent, callId = callId)
}

@Serializable
data class RecentCallCacheData(val entries: List<RecentCallEntry> = emptyList())

/** JSON serializer for the cache file; a corrupt file reads as empty. */
object RecentCallCacheSerializer : Serializer<RecentCallCacheData> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val defaultValue = RecentCallCacheData()
    override suspend fun readFrom(input: InputStream): RecentCallCacheData {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return runCatching { json.decodeFromString(RecentCallCacheData.serializer(), text) }.getOrDefault(defaultValue)
    }
    override suspend fun writeTo(t: RecentCallCacheData, output: OutputStream) {
        output.write(json.encodeToString(RecentCallCacheData.serializer(), t).encodeToByteArray())
    }
}

/**
 * Recent calls known to the app, filled by the `recent_caller` push and by `GET /recent-callers` (Requirement 33.9).
 * Entries older than [RETENTION_MS] are purged on every read and write. A JSON DataStore, so no Room migration.
 */
class RecentCallCache(
    private val dataStore: DataStore<RecentCallCacheData>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The cached record of [e164], or null when absent or older than 60 minutes. */
    suspend fun get(e164: String): RecentCall? =
        dataStore.data.first().entries.firstOrNull { it.e164 == e164 && !expired(it, nowMs()) }?.toRecentCall()

    /** Adds or updates one entry; an older record never replaces a newer one. */
    suspend fun put(entry: RecentCallEntry) {
        dataStore.updateData { data ->
            val kept = data.entries.filter { !expired(it, nowMs()) }
            val existing = kept.firstOrNull { it.e164 == entry.e164 }
            if (existing != null && existing.endMs >= entry.endMs) {
                RecentCallCacheData(kept)
            } else {
                RecentCallCacheData(kept.filter { it.e164 != entry.e164 } + entry)
            }
        }
    }

    /** Replaces all entries with the server list (task 12.22). */
    suspend fun replaceAll(entries: List<RecentCallEntry>) {
        dataStore.updateData { RecentCallCacheData(entries.filter { !expired(it, nowMs()) }.distinctBy { it.e164 }) }
    }

    /** Removes expired entries. */
    suspend fun purge() {
        dataStore.updateData { data -> RecentCallCacheData(data.entries.filter { !expired(it, nowMs()) }) }
    }

    suspend fun all(): List<RecentCallEntry> = dataStore.data.first().entries.filter { !expired(it, nowMs()) }

    private fun expired(e: RecentCallEntry, now: Long) = now - e.endMs > RETENTION_MS

    companion object {
        const val RETENTION_MS = 60 * 60_000L

        fun create(context: Context): RecentCallCache {
            val app = context.applicationContext
            return RecentCallCache(
                DataStoreFactory.create(
                    serializer = RecentCallCacheSerializer,
                    produceFile = { File(app.filesDir, "datastore/recent_calls.json") },
                ),
            )
        }
    }
}
