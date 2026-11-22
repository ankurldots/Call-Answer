package com.callanswer.app.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.callanswer.app.data.CallAnswerDatabase
import com.callanswer.app.data.OutboxDao
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Payload of an [OutboxKinds.ALERT] item: a notification that failed to post. */
@Serializable
data class AlertOutboxPayload(val kind: String, val callerNumber: String? = null, val extra: String? = null)

object OutboxKinds {
    const val ALERT = "ALERT"
}

/** Delivers one outbox payload; returns true when delivered. */
fun interface OutboxHandler {
    suspend fun deliver(payload: String): Boolean
}

/**
 * Flushes outbox items oldest first. An item is deleted only after its handler reports delivery
 * (8.12); failures and unknown kinds stay queued with an incremented attempt count.
 * Returns the number of items still pending.
 */
suspend fun flushOutbox(dao: OutboxDao, handlers: Map<String, OutboxHandler>): Int {
    for (item in dao.pending()) {
        val ok = try {
            handlers[item.kind]?.deliver(item.payload) ?: false
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (ok) dao.delete(item.id) else dao.recordAttempt(item.id)
    }
    return dao.count()
}

/** WorkManager worker that flushes the outbox; runs without a network constraint (8.12). */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = CallAnswerDatabase.create(applicationContext)
        try {
            val handlers = defaultHandlers(NotificationCenter(applicationContext))
            val remaining = flushOutbox(db.outboxDao(), handlers)
            return if (remaining == 0) Result.success() else Result.retry()
        } finally {
            db.close()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "outbox-flush"
        private val json = Json { ignoreUnknownKeys = true }

        fun encodeAlert(payload: AlertOutboxPayload): String = json.encodeToString(payload)

        fun defaultHandlers(center: NotificationCenter): Map<String, OutboxHandler> = mapOf(
            OutboxKinds.ALERT to OutboxHandler { raw ->
                val p = json.decodeFromString<AlertOutboxPayload>(raw)
                val kind = AlertKind.entries.firstOrNull { it.name == p.kind } ?: return@OutboxHandler true
                center.postAlert(kind, p.callerNumber, p.extra)
                true
            },
        )

        private val constraints: Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()

        /** Enqueues a flush; REPLACE is avoided so a running flush is not interrupted. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /**
         * Registers a network callback that triggers an extra flush whenever a network becomes
         * available. Returns the callback so the caller can unregister it.
         */
        fun registerConnectivityTrigger(context: Context): ConnectivityManager.NetworkCallback {
            val app = context.applicationContext
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = enqueue(app)
            }
            cm.registerDefaultNetworkCallback(callback)
            return callback
        }
    }
}
