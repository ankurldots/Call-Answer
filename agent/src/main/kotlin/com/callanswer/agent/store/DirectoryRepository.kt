package com.callanswer.agent.store

import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.TableKeys
import com.callanswer.core.tenant.UserId

data class NumberAssignment(val e164: E164, val userId: UserId, val assignedAtMs: Long, val assignedBy: String)
data class OpenCall(val userId: UserId, val callId: String, val startedAtEpochMs: Long)

/**
 * Cross-user lookups for the Agent and the Admin API: number to User, user listing, and open-call markers.
 * It never reads call, trace, or settings content.
 */
class DirectoryRepository(private val store: ItemStore) {

    /** Strongly consistent lookup of the User that owns [e164]. */
    suspend fun lookupNumber(e164: E164): NumberAssignment? =
        store.get(ItemKey(Keys.number(e164), Keys.ASSIGNMENT_SK), consistent = true)?.toAssignment(e164)

    /** The number assigned to [userId], if any. */
    suspend fun numberOf(userId: UserId): E164? =
        store.get(ItemKey(Keys.user(userId), Keys.ASSIGNMENT_SK), consistent = true)?.str("e164")?.let { runCatching { E164(it) }.getOrNull() }

    /** Assigns in one transaction. Returns false when the number or the User already has an assignment. */
    suspend fun assign(e164: E164, userId: UserId, assignedAtMs: Long, assignedBy: String): Boolean = try {
        store.transact(listOf(
            TransactOp.Put(
                StoreItem(Keys.number(e164), Keys.ASSIGNMENT_SK, mapOf(
                    "userId" to AttrValue.S(userId.sub), "assignedAt" to AttrValue.N(assignedAtMs), "assignedBy" to AttrValue.S(assignedBy),
                    "gsi1pk" to AttrValue.S(TableKeys.GSI_ASSIGNMENTS), "gsi1sk" to AttrValue.S(Keys.number(e164)),
                )),
                Condition.NotExists,
            ),
            TransactOp.Put(
                StoreItem(Keys.user(userId), Keys.ASSIGNMENT_SK, mapOf("e164" to AttrValue.S(e164.value))),
                Condition.NotExists,
            ),
        ))
        true
    } catch (e: TransactionCancelledException) {
        false
    }

    /** Removes both items of the User's assignment in one transaction. Returns false when there is none. */
    suspend fun unassign(userId: UserId): Boolean {
        val e164 = numberOf(userId) ?: return false
        return try {
            store.transact(listOf(
                TransactOp.Delete(ItemKey(Keys.number(e164), Keys.ASSIGNMENT_SK), Condition.AttrEquals("userId", AttrValue.S(userId.sub))),
                TransactOp.Delete(ItemKey(Keys.user(userId), Keys.ASSIGNMENT_SK), Condition.Exists),
            ))
            true
        } catch (e: TransactionCancelledException) {
            false
        }
    }

    /** All Users (`gsi1pk = USERS`). */
    suspend fun listUsers(): List<UserId> =
        store.queryAll(StoreQuery(TableKeys.GSI_USERS, index = IndexName.GSI1)).mapNotNull { TableKeys.parseUserPk(it.pk) }

    /** Active Users only, for the retention sweeper. */
    suspend fun listActiveUsers(): List<UserId> =
        store.queryAll(StoreQuery(TableKeys.GSI_USERS, index = IndexName.GSI1))
            .filter { it.str("status") != "DEACTIVATED" }.mapNotNull { TableKeys.parseUserPk(it.pk) }

    suspend fun listAssignments(): List<NumberAssignment> =
        store.queryAll(StoreQuery(TableKeys.GSI_ASSIGNMENTS, index = IndexName.GSI1)).mapNotNull { item ->
            val e = item.pk.removePrefix("NUMBER#").let { runCatching { E164(it) }.getOrNull() } ?: return@mapNotNull null
            item.toAssignment(e)
        }

    /** Calls that were in progress (`gsi1pk = OPENCALLS`), oldest first. */
    suspend fun listOpenCalls(): List<OpenCall> =
        store.queryAll(StoreQuery(TableKeys.GSI_OPEN_CALLS, index = IndexName.GSI1)).mapNotNull { item ->
            TableKeys.parseOpenGsiSk(item.str("gsi1sk") ?: return@mapNotNull null)?.let { (ms, u, id) -> OpenCall(u, id, ms) }
        }

    private fun StoreItem.toAssignment(e164: E164): NumberAssignment? {
        val u = str("userId")?.let { runCatching { UserId(it) }.getOrNull() } ?: return null
        return NumberAssignment(e164, u, num("assignedAt") ?: 0L, str("assignedBy") ?: "")
    }
}
