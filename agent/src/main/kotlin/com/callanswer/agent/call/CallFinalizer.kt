package com.callanswer.agent.call

import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.obs.TraceEvent
import com.callanswer.agent.obs.TraceEventName
import com.callanswer.agent.store.CostCounter
import com.callanswer.agent.store.RecordingStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.engine.CallSession
import com.callanswer.core.engine.ConversationState
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.CallLogEntryBuilder
import com.callanswer.core.model.CostRates
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.LatencyReport
import com.callanswer.core.model.LogNoteKind
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.RecordingInfo
import com.callanswer.core.model.RecordingStatus
import com.callanswer.core.model.SonicSessionRecord
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.VoiceSubstitution
import com.callanswer.core.tenant.UserId
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.tenant.E164
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Sends the call-end event (FCM `CALL_ENDED` and the live `call_ended` event). Carries identifiers only. */
fun interface CallEndNotifier {
    suspend fun callEnded(userId: UserId, callId: String, outcome: Outcome, durationSec: Int)
}

/** Sends the `recent_caller` FCM data message (number, endMs, intent, callId) to the User's devices. Best effort. */
fun interface RecentCallerNotifier {
    suspend fun recentCaller(userId: UserId, number: String, endMs: Long, intent: IntentCategory, callId: String)
}

/** Everything the finalizer needs from the ended call. No raw audio or text other than the transcript in [state]. */
class FinalizeInput(
    val session: CallSession,
    val state: ConversationState,
    val trace: CallTrace,
    val endedAtMs: Long,
    val outcome: Outcome,
    val endReason: EndReason,
    val rates: CostRates,
    val monthlyLimitInr: Int?,
    /** Reply latencies measured by the session: caller end of speech to first agent audio, in milliseconds. */
    val replyLatenciesMs: List<Long> = emptyList(),
    val callerAudioSec: Long = 0,
    val agentAudioSec: Long = 0,
    /** Gate numbers that tool results of this call contained. */
    val toolGateNumbers: Set<String> = emptySet(),
    val wavRecording: ByteArray? = null,
    val sonicSessions: List<SonicSessionRecord> = emptyList(),
    val fallbackCause: String? = null,
    val isTestCall: Boolean = false,
    val voiceId: String? = null,
    val settingsVersion: Long? = null,
    val notes: List<LogNoteKind> = emptyList(),
    val voiceSubstitutions: List<VoiceSubstitution> = emptyList(),
)

data class FinalizeResult(
    val entry: CallLogEntry,
    /** True when the call entry was stored (false after the retries failed; the NOT_LOGGED alert has fired). */
    val stored: Boolean,
    /** Gate numbers the Agent said that no tool result of the call contained. */
    val gateMismatches: List<String>,
)

/** The call trace as stored in the `TRACE#<callId>` item: events plus the Latency_Report (Requirement 11.9). */
@Serializable
data class StoredTrace(val events: List<TraceEvent>, val latency: LatencyReport, val gateMismatchCount: Int = 0)

/** Post-call check of the gate numbers spoken against the tool results (Requirement 18.3). */
object GateNumberCheck {
    private val SPOKEN = Regex("(?i)(?:gate|गेट)\\s*(?:number|no\\.?|नंबर)?\\s*[:#-]?\\s*([0-9]+[A-Za-z]?)")

    /** Gate numbers named in Agent utterances. */
    fun spoken(text: String): List<String> = SPOKEN.findAll(text).map { it.groupValues[1].uppercase() }.toList()

    /** Distinct spoken numbers that are not in [allowed]. */
    fun mismatches(agentTexts: List<String>, allowed: Set<String>): List<String> {
        val ok = allowed.map { it.uppercase() }.toSet()
        return agentTexts.flatMap(::spoken).filter { it !in ok }.distinct()
    }
}

object LatencyCalculator {
    fun report(ttfaMs: Long?, replies: List<Long>): LatencyReport {
        if (replies.isEmpty()) return LatencyReport(ttfaMs, null, null)
        val s = replies.sorted()
        val mid = s.size / 2
        val median = if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
        return LatencyReport(ttfaMs, median, s.last())
    }
}

/**
 * Runs when the Voice_Stream ends (Requirement 1.4). Computes the entry fields, stores the entry and the trace, and
 * sends the end event. Slow steps (recording upload, end event) are bounded so the whole run stays within 5 seconds.
 */
class CallFinalizer(
    private val userId: UserId,
    private val repo: UserScopedRepository,
    private val costs: CostCounter,
    private val recordings: RecordingStore,
    private val notifier: CallEndNotifier = CallEndNotifier { _, _, _, _ -> },
    private val uploadTimeoutMs: Long = 2_000,
    private val notifyTimeoutMs: Long = 1_000,
    private val recentNotifier: RecentCallerNotifier = RecentCallerNotifier { _, _, _, _, _ -> },
    /** Bound for recording the recent call, including its retries (3 retries at 1-second intervals). */
    private val recentRecordTimeoutMs: Long = 3_500,
    private val recentPushTimeoutMs: Long = 3_000,
    private val recentRetryPause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun finalize(input: FinalizeInput): FinalizeResult {
        val s = input.session
        val trace = input.trace
        val events = trace.events()
        val ttfa = events.firstOrNull { it.event == TraceEventName.FIRST_AGENT_AUDIO }?.tMs
        val latency = LatencyCalculator.report(ttfa, input.replyLatenciesMs)

        val agentTexts = input.state.transcript.filter { it.speaker == Speaker.AGENT }.map { it.text }
        val mismatches = GateNumberCheck.mismatches(agentTexts, input.toolGateNumbers)
        if (mismatches.isNotEmpty()) trace.recordError("finalizer", "GATE_NUMBER_MISMATCH")

        val callSec = (input.endedAtMs.coerceAtLeast(0L) / 1000L)
        val nowMs = s.startedAt.toEpochMilli() + input.endedAtMs.coerceAtLeast(0L)
        val estimate = try {
            costs.recordCall(input.rates, callSec, input.callerAudioSec, input.agentAudioSec, input.monthlyLimitInr, nowMs).estimatePaise
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            trace.recordError("finalizer", "COST_FAILED")
            null
        }

        val recordingKey = input.wavRecording?.let { wav ->
            try {
                withTimeoutOrNull(uploadTimeoutMs) { recordings.upload(userId, s.id, wav) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (input.wavRecording != null && recordingKey == null) trace.recordError("finalizer", "RECORDING_FAILED")

        trace.record(TraceEventName.CALL_END, "finalizer", "OK", durationMs = input.endedAtMs.coerceAtLeast(0L))

        val base = CallLogEntryBuilder.build(
            session = s, state = input.state, endedAtMs = input.endedAtMs,
            outcome = input.outcome, endReason = input.endReason,
            notes = input.notes, voiceSubstitutions = input.voiceSubstitutions,
        )
        val entry = base.copy(
            costEstimatePaise = estimate,
            latency = latency,
            sonicSessions = input.sonicSessions,
            fallbackCause = input.fallbackCause,
            recordingKey = recordingKey,
            recording = if (recordingKey != null) base.recording else RecordingInfo(RecordingStatus.NOT_FOUND),
            isTestCall = input.isTestCall,
            voiceId = input.voiceId,
            settingsVersion = input.settingsVersion,
        )

        val stored = repo.putCall(entry)
        val tracedCount = trace.events().size
        try {
            val doc = StoredTrace(trace.events(), latency, mismatches.size)
            repo.putTrace(s.id, TRACE_JSON.encodeToString(StoredTrace.serializer(), doc), trace.ttlEpochSeconds())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The call entry is the record that matters; a lost trace is not retried.
        }
        try {
            repo.deleteOpenMarker(s.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The retention sweeper clears stale markers.
        }
        coroutineScope {
            // The recent-call work runs beside the end event, so it adds nothing to the finalization budget.
            val recent = async { recordRecent(entry, nowMs, trace) }
            try {
                withTimeoutOrNull(notifyTimeoutMs) { notifier.callEnded(userId, s.id, entry.outcome, entry.durationSec) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The end event is best effort.
            }
            recent.await()
        }
        if (trace.events().size > tracedCount) {
            // Recent-call errors were recorded after the first trace write; store the trace again.
            try {
                val doc = StoredTrace(trace.events(), latency, mismatches.size)
                repo.putTrace(s.id, TRACE_JSON.encodeToString(StoredTrace.serializer(), doc), trace.ttlEpochSeconds())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
        return FinalizeResult(entry, stored, mismatches)
    }

    /**
     * Requirements 32.6, 33.8: records the recent call and pushes `recent_caller`, for Agent-handled calls that are not
     * SOLICITATION and have a visible number. Each step is its own try block; neither can fail the Call_Log.
     */
    private suspend fun recordRecent(entry: CallLogEntry, endMs: Long, trace: CallTrace) = coroutineScope {
        if (entry.outcome == Outcome.RING_THROUGH_ANSWERED || entry.outcome == Outcome.RING_THROUGH_UNANSWERED || entry.dialLegs != null) return@coroutineScope
        if (entry.intent == IntentCategory.SOLICITATION) return@coroutineScope
        val number = entry.callerNumber?.let { n -> try { E164(n) } catch (e: IllegalArgumentException) { null } }
            ?: return@coroutineScope
        val push = async {
            try {
                withTimeoutOrNull(recentPushTimeoutMs) { recentNotifier.recentCaller(userId, number.value, endMs, entry.intent, entry.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trace.recordError("recent_push", "PUSH_FAILED")
            }
        }
        val ok = try {
            withTimeoutOrNull(recentRecordTimeoutMs) {
                var done = false
                for (attempt in 0..RECENT_RETRIES) {
                    if (attempt > 0) recentRetryPause(RECENT_RETRY_INTERVAL_MS)
                    try {
                        repo.recordEnded(number, endMs, entry.intent, entry.id)
                        done = true
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // retry
                    }
                }
                done
            } ?: false
        } catch (e: CancellationException) {
            throw e
        }
        if (!ok) trace.recordError("recent_call", "RECENT_RECORD_FAILED")
        push.await()
    }

    private companion object {
        const val RECENT_RETRIES = 3
        const val RECENT_RETRY_INTERVAL_MS = 1_000L
        val TRACE_JSON = Json { encodeDefaults = true }
    }
}
