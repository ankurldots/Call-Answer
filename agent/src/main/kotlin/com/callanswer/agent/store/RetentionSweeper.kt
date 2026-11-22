package com.callanswer.agent.store

import com.callanswer.core.cloud.RetentionPolicy
import com.callanswer.core.tenant.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Deletes each active User's call items, trace items, and S3 objects (recordings and transcripts under
 * `users/<sub>/`) once start time plus that User's retention is not after now. Runs at start and every 6 hours.
 * Every step is idempotent, so a failed run is simply retried.
 */
class RetentionSweeper(
    private val directory: DirectoryRepository,
    private val settings: SettingsStore,
    private val items: ItemStore,
    private val blobs: BlobStore,
    private val recordings: RecordingStore = RecordingStore(blobs) { _, _ -> error("not used") },
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private fun repo(u: UserId) = UserScopedRepository(u, items, blobs)

    /** One pass over all active Users. Returns the number of calls removed. A failing User does not stop the others. */
    suspend fun sweepOnce(): Int {
        var removed = 0
        for (user in directory.listActiveUsers()) {
            try {
                removed += withRetries { sweepUser(user) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("retention_sweep_failed user=${user.sub} cause=${e.javaClass.simpleName}")
            }
        }
        return removed
    }

    /** Sweeps one User with that User's retention. */
    suspend fun sweepUser(user: UserId): Int {
        val days = settings.get(user).document.retentionDays
        val t = now()
        val r = repo(user)
        val expired = RetentionPolicy.select(
            r.callRefsStartedAtOrBefore(RetentionPolicy.cutoffMs(t, days)), t, days,
        ) { it.startedAtEpochMs }
        expired.forEach { removeCall(user, it.startedAtEpochMs, it.callId) }
        return expired.size
    }

    /** Immediate deletion of one call and everything stored for it. Returns false when the call does not exist. */
    suspend fun deleteCallNow(user: UserId, callId: String): Boolean {
        val r = repo(user)
        val entry = r.findCall(callId) ?: return false
        removeCall(user, entry.startedAtEpochMs, callId)
        return true
    }

    // Blobs and trace first, the call item last, so a failure midway leaves the item for the next run.
    private suspend fun removeCall(user: UserId, startedAtEpochMs: Long, callId: String) {
        val r = repo(user)
        recordings.delete(user, callId)
        r.deleteTrace(callId)
        r.deleteCall(startedAtEpochMs, callId)
    }

    /** Runs [sweepOnce] now and every [INTERVAL_MS] until the scope ends. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try {
                sweepOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("retention_sweep_run_failed cause=${e.javaClass.simpleName}")
            }
            delay(INTERVAL_MS)
        }
    }

    private suspend fun <T> withRetries(block: suspend () -> T): T {
        var last: Exception? = null
        repeat(RETRIES + 1) { attempt ->
            if (attempt > 0) pause(RETRY_INTERVAL_MS)
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last!!
    }

    companion object {
        const val INTERVAL_MS: Long = 6L * 60 * 60 * 1000
        const val RETRIES: Int = 3
        const val RETRY_INTERVAL_MS: Long = 1000
    }
}
