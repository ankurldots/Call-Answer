package com.callanswer.agent.store

import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.PhoneNumbers
import com.callanswer.core.tenant.UserId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

enum class OutOfServiceReason { INVALID_NUMBER, UNASSIGNED, USER_INACTIVE }

sealed interface TenantResolution {
    data class Resolved(val userId: UserId, val e164: E164) : TenantResolution
    data class OutOfService(val reason: OutOfServiceReason) : TenantResolution
}

/** The only facts about a `ForwardedFrom` value that leave the resolver. The value itself is never kept. */
data class ForwardedFromHint(val hintPresent: Boolean, val hintMatchesUserMobile: Boolean)

/** Writes `OPS#UNASSIGNED#<date>` items: the called number and the reason, never the Caller number. */
class UnassignedCallLog(private val store: ItemStore, private val nowMs: () -> Long = System::currentTimeMillis) {
    suspend fun record(to: String, reason: OutOfServiceReason) {
        val now = nowMs()
        val date = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        store.put(StoreItem(
            pk = "$PK_PREFIX$date",
            sk = "${now.coerceAtLeast(0).toString().padStart(13, '0')}#${UUID.randomUUID()}",
            attrs = mapOf("to" to AttrValue.S(to), "reason" to AttrValue.S(reason.name), "atMs" to AttrValue.N(now)),
        ))
    }

    companion object { const val PK_PREFIX = "OPS#UNASSIGNED#" }
}

/**
 * Maps the called number (`To`) to exactly one User. `ForwardedFrom` is a hint only and never takes part in the lookup.
 * Hits and misses are cached for [ttlMs]; Admin writes call [clearCache].
 */
class TenantResolver(
    private val store: ItemStore,
    private val unassignedLog: UnassignedCallLog? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 30_000L,
) {
    private val directory = DirectoryRepository(store)
    private val lock = Mutex()
    private data class Entry(val result: TenantResolution, val expiresAtMs: Long)
    private val cache = HashMap<E164, Entry>()

    suspend fun resolve(to: String?): TenantResolution {
        val e164 = to?.let { PhoneNumbers.normalize(it) }
        if (e164 == null) {
            val r = TenantResolution.OutOfService(OutOfServiceReason.INVALID_NUMBER)
            logUnassigned("invalid", r.reason)
            return r
        }
        val now = nowMs()
        lock.withLock { cache[e164]?.takeIf { it.expiresAtMs > now } }?.let { return it.result }
        val result = lookup(e164)
        lock.withLock { cache[e164] = Entry(result, nowMs() + ttlMs) }
        if (result is TenantResolution.OutOfService) logUnassigned(e164.value, result.reason)
        return result
    }

    /** Compares the hint with the User_Mobile. The raw value is not returned. */
    fun hint(forwardedFrom: String?, userMobile: E164?): ForwardedFromHint {
        val present = !forwardedFrom.isNullOrBlank()
        val matches = present && userMobile != null && PhoneNumbers.normalize(forwardedFrom!!) == userMobile
        return ForwardedFromHint(present, matches)
    }

    suspend fun clearCache() { lock.withLock { cache.clear() } }

    private suspend fun lookup(e164: E164): TenantResolution {
        val assignment = directory.lookupNumber(e164) ?: return TenantResolution.OutOfService(OutOfServiceReason.UNASSIGNED)
        val profile = store.get(ItemKey(Keys.user(assignment.userId), Keys.PROFILE_SK), consistent = true)
        val active = profile != null && profile.str("status") != "DEACTIVATED"
        return if (active) TenantResolution.Resolved(assignment.userId, e164)
        else TenantResolution.OutOfService(OutOfServiceReason.USER_INACTIVE)
    }

    private suspend fun logUnassigned(to: String, reason: OutOfServiceReason) {
        try { unassignedLog?.record(to, reason) } catch (_: Exception) { /* the log never blocks a call */ }
    }
}

sealed interface AssignResult {
    data class Assigned(val e164: E164) : AssignResult
    data object InvalidNumber : AssignResult
    /** The number equals a User's `userMobile` (loop protection). */
    data object NumberIsUserMobile : AssignResult
    /** HTTP 409: the number or the User already has an assignment. */
    data object Conflict : AssignResult
}

/** The Admin's record of the manual Exotel flow setup (checklist step 6 of the design). */
data class FlowConfirmation(val configured: Boolean, val fallbackMobile: E164?, val configuredAtMs: Long?)

class NumberAssignmentService(
    private val store: ItemStore,
    private val resolver: TenantResolver? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val directory = DirectoryRepository(store)

    suspend fun assign(rawNumber: String, userId: UserId, assignedBy: String): AssignResult {
        val e164 = PhoneNumbers.normalize(rawNumber) ?: return AssignResult.InvalidNumber
        if (isAnyUserMobile(e164)) return AssignResult.NumberIsUserMobile
        return try {
            store.transact(listOf(
                TransactOp.Put(
                    StoreItem(Keys.number(e164), Keys.ASSIGNMENT_SK, mapOf(
                        "userId" to AttrValue.S(userId.sub), "assignedAt" to AttrValue.N(nowMs()), "assignedBy" to AttrValue.S(assignedBy),
                        "flowConfigured" to AttrValue.N(0),
                        "gsi1pk" to AttrValue.S(com.callanswer.core.tenant.TableKeys.GSI_ASSIGNMENTS), "gsi1sk" to AttrValue.S(Keys.number(e164)),
                    )),
                    Condition.NotExists,
                ),
                TransactOp.Put(
                    StoreItem(Keys.user(userId), Keys.ASSIGNMENT_SK, mapOf("e164" to AttrValue.S(e164.value))),
                    Condition.NotExists,
                ),
            ))
            AssignResult.Assigned(e164)
        } catch (e: TransactionCancelledException) {
            AssignResult.Conflict
        } finally {
            resolver?.clearCache()
        }
    }

    /** Returns false when the User has no assignment. */
    suspend fun unassign(userId: UserId): Boolean = try { directory.unassign(userId) } finally { resolver?.clearCache() }

    /** Returns false when the User has no profile. */
    suspend fun deactivate(userId: UserId): Boolean = try {
        store.update(ItemKey(Keys.user(userId), Keys.PROFILE_SK), ItemUpdate(set = mapOf("status" to AttrValue.S("DEACTIVATED"))), Condition.Exists)
        true
    } catch (e: ConditionFailedException) {
        false
    } finally {
        resolver?.clearCache()
    }

    /** Records the Admin's confirmation that the Exotel flow of [e164] is set up. Returns false when the number is not assigned. */
    suspend fun confirmFlow(e164: E164, fallbackMobile: E164): Boolean = try {
        store.update(
            ItemKey(Keys.number(e164), Keys.ASSIGNMENT_SK),
            ItemUpdate(set = mapOf(
                "flowConfigured" to AttrValue.N(1), "flowFallbackMobile" to AttrValue.S(fallbackMobile.value),
                "flowConfiguredAt" to AttrValue.N(nowMs()),
            )),
            Condition.Exists,
        )
        true
    } catch (e: ConditionFailedException) {
        false
    }

    suspend fun flowConfirmation(e164: E164): FlowConfirmation? =
        store.get(ItemKey(Keys.number(e164), Keys.ASSIGNMENT_SK))?.let {
            FlowConfirmation(
                it.num("flowConfigured") == 1L,
                it.str("flowFallbackMobile")?.let { m -> runCatching { E164(m) }.getOrNull() },
                it.num("flowConfiguredAt"),
            )
        }

    suspend fun list(): List<NumberAssignment> = directory.listAssignments()

    /** True when [e164] is the `userMobile` of any User's stored settings. */
    private suspend fun isAnyUserMobile(e164: E164): Boolean =
        directory.listUsers().any { u ->
            val item = store.get(ItemKey(Keys.user(u), Keys.SETTINGS_SK)) ?: return@any false
            val mobile = item.str("userMobile") ?: item.attrs.values.firstNotNullOfOrNull { v ->
                (v as? AttrValue.S)?.value?.takeIf { it.startsWith("{") }?.let { userMobileOf(it) }
            }
            mobile?.let { PhoneNumbers.normalize(it) } == e164
        }

    private fun userMobileOf(json: String): String? = runCatching {
        ((Json.parseToJsonElement(json) as? JsonObject)?.get("userMobile") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()
}
