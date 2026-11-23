package com.callanswer.app.sms

import android.util.Log
import com.callanswer.app.api.ApiException
import com.callanswer.app.data.OutboxDao
import com.callanswer.app.data.OutboxEntity
import com.callanswer.app.data.OutboxKinds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Reports a Map_SMS result with `POST /calls/{id}/map-sms-result` within [timeoutMs] (5 s, Requirement 10.13). A network
 * or server failure, or no answer in time, puts the result in the Outbox; [flush] sends the queued ones later. A 4xx
 * answer other than 401 and 429 is final and drops the item.
 */
class MapSmsReporter(
    private val post: suspend (callId: String, result: MapSmsResult) -> Unit,
    private val outbox: OutboxDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = REPORT_TIMEOUT_MS,
) : MapSmsResultSink {

    override suspend fun report(callId: String, result: MapSmsResult) {
        val outcome = attempt(callId, result, timeoutMs)
        if (outcome == Outcome.RETRY) {
            outbox.enqueue(
                OutboxEntity(
                    kind = OutboxKinds.MAP_SMS_RESULT,
                    payload = buildJsonObject { put("callId", callId); put("status", result.name) }.toString(),
                    createdAtEpochMs = nowMs(),
                ),
            )
        }
    }

    /** Sends the queued results, oldest first, and stops at the first one that needs a retry. Call when the network is back. */
    suspend fun flush() {
        for (item in outbox.pending()) {
            if (item.kind != OutboxKinds.MAP_SMS_RESULT) continue
            val parsed = runCatching {
                val json = Json.parseToJsonElement(item.payload).jsonObject
                json.getValue("callId").jsonPrimitive.content to MapSmsResult.valueOf(json.getValue("status").jsonPrimitive.content)
            }.getOrNull()
            if (parsed != null) {
                outbox.recordAttempt(item.id)
                if (attempt(parsed.first, parsed.second, FLUSH_TIMEOUT_MS) == Outcome.RETRY) return
            }
            outbox.delete(item.id)
        }
    }

    private enum class Outcome { DONE, RETRY }

    private suspend fun attempt(callId: String, result: MapSmsResult, timeout: Long): Outcome =
        try {
            val done = withTimeoutOrNull(timeout) { post(callId, result); true }
            if (done == true) Outcome.DONE else Outcome.RETRY
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            when (e) {
                is ApiException.Network, is ApiException.Server, is ApiException.Unauthorized, is ApiException.RateLimited -> Outcome.RETRY
                else -> {
                    runCatching { Log.w(TAG, "map SMS result rejected: ${e.javaClass.simpleName}") }
                    Outcome.DONE
                }
            }
        }

    companion object {
        const val REPORT_TIMEOUT_MS = 5_000L
        private const val FLUSH_TIMEOUT_MS = 15_000L
        private const val TAG = "MapSmsReporter"
    }
}
