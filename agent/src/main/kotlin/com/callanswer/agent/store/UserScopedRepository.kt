package com.callanswer.agent.store

import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.Utterance
import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.TableKeys
import com.callanswer.core.tenant.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString

/** Raised for the `NOT_LOGGED` alert when a call entry could not be stored after the retries. */
fun interface NotLoggedAlert {
    suspend fun notLogged(userId: UserId, callId: String)
}

@Serializable data class ConsentRecord(val version: String, val acceptedAtMs: Long, val withdrawnAtMs: Long? = null)
@Serializable data class DeviceRecord(val deviceId: String, val fcmToken: String, val updatedAtMs: Long)
@Serializable data class ForwardTestRecord(val armedAtMs: Long, val resultCallId: String? = null)
data class ProfileRecord(val status: String, val createdAtMs: Long)
data class CostRecord(val totalPaise: Long, val notified80: Boolean, val notified100: Boolean)
data class CallRef(val startedAtEpochMs: Long, val callId: String)
data class CallPage(val entries: List<CallLogEntry>, val next: ItemKey?)

/**
 * Everything one User owns. Each method builds its partition key from [userId] through `Keys`, so no method takes a
 * raw partition key and no method can read another User's items.
 */
class UserScopedRepository(
    private val userId: UserId,
    private val store: ItemStore,
    private val blobs: BlobStore,
    private val alert: NotLoggedAlert = NotLoggedAlert { _, _ -> },
    private val log: (String) -> Unit = {},
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val pk: String = Keys.user(userId)

    private fun key(sk: String) = ItemKey(pk, sk)

    // PROFILE

    /** Creates the profile if absent. Returns false when one already exists. */
    suspend fun createProfileIfAbsent(createdAtMs: Long): Boolean = try {
        store.put(
            StoreItem(pk, Keys.PROFILE_SK, mapOf(
                "status" to AttrValue.S("ACTIVE"), "createdAt" to AttrValue.N(createdAtMs),
                "gsi1pk" to AttrValue.S(TableKeys.GSI_USERS), "gsi1sk" to AttrValue.S(TableKeys.userGsiSk(userId)),
            )),
            Condition.NotExists,
        )
        true
    } catch (e: ConditionFailedException) {
        false
    }

    suspend fun getProfile(): ProfileRecord? = store.get(key(Keys.PROFILE_SK))?.let {
        ProfileRecord(it.str("status") ?: "ACTIVE", it.num("createdAt") ?: 0L)
    }

    suspend fun setProfileStatus(status: String) {
        require(status == "ACTIVE" || status == "DEACTIVATED") { "Unknown status" }
        store.update(key(Keys.PROFILE_SK), ItemUpdate(set = mapOf("status" to AttrValue.S(status))), Condition.Exists)
    }

    // CONSENT

    suspend fun putConsent(c: ConsentRecord) =
        store.put(StoreItem(pk, TableKeys.CONSENT_SK, mapOf("json" to AttrValue.S(CoreJson.encodeToString(c)))))

    suspend fun getConsent(): ConsentRecord? = json(key(TableKeys.CONSENT_SK))?.let { CoreJson.decodeFromString<ConsentRecord>(it) }

    // DEVICE#

    /** Stores a device. When the User already has [MAX_DEVICES] other devices, the oldest is removed. */
    suspend fun putDevice(d: DeviceRecord) {
        val sk = TableKeys.deviceSk(d.deviceId)
        val existing = listDevices()
        if (existing.none { it.deviceId == d.deviceId } && existing.size >= MAX_DEVICES) {
            existing.sortedBy { it.updatedAtMs }.take(existing.size - MAX_DEVICES + 1)
                .forEach { store.delete(key(TableKeys.deviceSk(it.deviceId))) }
        }
        store.put(StoreItem(pk, sk, mapOf("json" to AttrValue.S(CoreJson.encodeToString(d)))))
    }

    suspend fun listDevices(): List<DeviceRecord> =
        store.queryAll(StoreQuery(pk, SortCondition.BeginsWith(TableKeys.DEVICE_PREFIX), consistent = true))
            .mapNotNull { it.str("json")?.let { j -> CoreJson.decodeFromString<DeviceRecord>(j) } }

    suspend fun deleteDevice(deviceId: String) = store.delete(key(TableKeys.deviceSk(deviceId)))

    // CALL#

    /**
     * Stores a call entry. A transcript over [TRANSCRIPT_LIMIT_BYTES] goes to S3 under `users/<sub>/transcripts/`
     * and the item keeps a pointer. A failed write is retried [RETRIES] times at 1-second intervals; after that the
     * `NOT_LOGGED` alert fires, a log line without content is written, and the result is false.
     */
    suspend fun putCall(entry: CallLogEntry, ttlEpochSec: Long? = null): Boolean {
        val sk = TableKeys.callSk(entry.startedAtEpochMs, entry.id)
        val transcriptJson = CoreJson.encodeToString(ListSerializer(Utterance.serializer()), entry.transcript)
        val big = transcriptJson.toByteArray(Charsets.UTF_8).size > TRANSCRIPT_LIMIT_BYTES
        val stored = if (big) entry.copy(transcript = emptyList()) else entry
        val attrs = LinkedHashMap<String, AttrValue>()
        attrs["json"] = AttrValue.S(CoreJson.encodeToString(CallLogEntry.serializer(), stored))
        attrs["intent"] = AttrValue.S(entry.intent.name)
        attrs["outcome"] = AttrValue.S(entry.outcome.name)
        attrs["startMs"] = AttrValue.N(entry.startedAtEpochMs)
        entry.settingsVersion?.let { attrs["settingsVersion"] = AttrValue.N(it) }
        entry.recordingKey?.let { attrs["recordingKey"] = AttrValue.S(it) }
        ttlEpochSec?.let { attrs["ttl"] = AttrValue.N(it) }
        val transcriptKey = Keys.transcript(userId, entry.id)
        if (big) attrs["transcriptKey"] = AttrValue.S(transcriptKey)
        return withRetries(entry.id) {
            if (big) blobs.put(transcriptKey, transcriptJson.toByteArray(Charsets.UTF_8), "application/json")
            store.put(StoreItem(pk, sk, attrs))
        }
    }

    suspend fun getCall(startedAtEpochMs: Long, callId: String): CallLogEntry? =
        store.get(key(TableKeys.callSk(startedAtEpochMs, callId)))?.let { decodeCall(it, withTranscript = true) }

    /** Finds a call by identifier alone by paging this User's calls newest first. */
    suspend fun findCall(callId: String): CallLogEntry? {
        var cursor: ItemKey? = null
        do {
            val page = store.query(StoreQuery(pk, SortCondition.BeginsWith(TableKeys.CALL_PREFIX), forward = false, limit = 50, startAfter = cursor))
            page.items.firstOrNull { TableKeys.parseCallSk(it.sk)?.second == callId }?.let { return decodeCall(it, withTranscript = true) }
            cursor = page.lastKey
        } while (cursor != null)
        return null
    }

    /** The call list, newest first. A transcript kept in S3 is not loaded here; use [getCall] for the detail. */
    suspend fun listCalls(limit: Int, after: ItemKey? = null): CallPage {
        require(limit in 1..MAX_PAGE) { "Invalid page size" }
        val page = store.query(StoreQuery(pk, SortCondition.BeginsWith(TableKeys.CALL_PREFIX), forward = false, limit = limit, startAfter = after))
        return CallPage(page.items.map { decodeCall(it, withTranscript = false) }, page.lastKey)
    }

    /** References to calls that started at or before [startedAtOrBeforeMs], oldest first. */
    suspend fun callRefsStartedAtOrBefore(startedAtOrBeforeMs: Long): List<CallRef> =
        store.queryAll(StoreQuery(pk, SortCondition.Between(TableKeys.callSkFrom(), TableKeys.callSkUpTo(startedAtOrBeforeMs))))
            .mapNotNull { TableKeys.parseCallSk(it.sk)?.let { (ms, id) -> CallRef(ms, id) } }

    /** Deletes the call item and its S3 transcript, if any. Returns false when the call does not exist. */
    suspend fun deleteCall(startedAtEpochMs: Long, callId: String): Boolean {
        val k = key(TableKeys.callSk(startedAtEpochMs, callId))
        val item = store.get(k) ?: return false
        item.str("transcriptKey")?.let { blobs.delete(it) }
        store.delete(k)
        return true
    }

    // TRACE#

    suspend fun putTrace(callId: String, traceJson: String, ttlEpochSec: Long) = store.put(
        StoreItem(pk, TableKeys.traceSk(callId), mapOf("json" to AttrValue.S(traceJson), "ttl" to AttrValue.N(ttlEpochSec))),
    )
    suspend fun getTrace(callId: String): String? = json(key(TableKeys.traceSk(callId)))
    suspend fun deleteTrace(callId: String) = store.delete(key(TableKeys.traceSk(callId)))

    // COST#

    /** Atomic `ADD` to the month total. Returns the total after the add. */
    suspend fun addCost(yearMonth: String, paise: Long): Long =
        store.update(key(TableKeys.costSk(yearMonth)), ItemUpdate(add = mapOf("totalPaise" to paise))).num("totalPaise") ?: 0L

    suspend fun getCost(yearMonth: String): CostRecord? = store.get(key(TableKeys.costSk(yearMonth)))?.let {
        CostRecord(it.num("totalPaise") ?: 0L, (it.num("notified80") ?: 0L) != 0L, (it.num("notified100") ?: 0L) != 0L)
    }

    /**
     * Atomically claims a one-time notification flag (`notified80` or `notified100`) of the month. Returns true for
     * exactly one caller, the one whose `ADD` produced 1.
     */
    suspend fun claimCostNotification(yearMonth: String, flag: String): Boolean {
        require(flag == "notified80" || flag == "notified100") { "Invalid flag" }
        return store.update(key(TableKeys.costSk(yearMonth)), ItemUpdate(add = mapOf(flag to 1L))).num(flag) == 1L
    }

    // FWDTEST

    suspend fun putForwardTest(r: ForwardTestRecord) =
        store.put(StoreItem(pk, TableKeys.FWDTEST_SK, mapOf("json" to AttrValue.S(CoreJson.encodeToString(r)))))
    suspend fun getForwardTest(): ForwardTestRecord? = json(key(TableKeys.FWDTEST_SK))?.let { CoreJson.decodeFromString<ForwardTestRecord>(it) }
    suspend fun deleteForwardTest() = store.delete(key(TableKeys.FWDTEST_SK))

    // OPEN#

    suspend fun putOpenMarker(callId: String, startedAtEpochMs: Long) = store.put(
        StoreItem(pk, TableKeys.openSk(callId), mapOf(
            "startMs" to AttrValue.N(startedAtEpochMs),
            "gsi1pk" to AttrValue.S(TableKeys.GSI_OPEN_CALLS),
            "gsi1sk" to AttrValue.S(TableKeys.openGsiSk(startedAtEpochMs, userId, callId)),
        )),
    )
    suspend fun deleteOpenMarker(callId: String) = store.delete(key(TableKeys.openSk(callId)))

    // internals

    private suspend fun json(k: ItemKey): String? = store.get(k)?.str("json")

    private suspend fun decodeCall(item: StoreItem, withTranscript: Boolean): CallLogEntry {
        val entry = CoreJson.decodeFromString(CallLogEntry.serializer(), item.str("json") ?: error("Call item without json"))
        val tk = item.str("transcriptKey")
        if (!withTranscript || tk == null) return entry
        // The pointer is read from this User's own item; refuse one that points outside the User's prefix.
        check(tk.startsWith(Keys.s3Prefix(userId))) { "Transcript pointer outside the User prefix" }
        val bytes = blobs.get(tk) ?: return entry
        return entry.copy(transcript = CoreJson.decodeFromString(ListSerializer(Utterance.serializer()), bytes.toString(Charsets.UTF_8)))
    }

    private suspend fun withRetries(callId: String, block: suspend () -> Unit): Boolean {
        var last: Exception? = null
        for (attempt in 0..RETRIES) {
            if (attempt > 0) pause(RETRY_INTERVAL_MS)
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        // The line carries identifiers and the exception class only: no number, transcript, or message text.
        log("call_not_logged user=${userId.sub} call=$callId cause=${last?.javaClass?.simpleName}")
        try {
            alert.notLogged(userId, callId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("not_logged_alert_failed user=${userId.sub} call=$callId")
        }
        return false
    }

    companion object {
        const val TRANSCRIPT_LIMIT_BYTES: Int = 300 * 1024
        const val RETRIES: Int = 3
        const val RETRY_INTERVAL_MS: Long = 1000
        const val MAX_DEVICES: Int = 5
        const val MAX_PAGE: Int = 200
    }
}
