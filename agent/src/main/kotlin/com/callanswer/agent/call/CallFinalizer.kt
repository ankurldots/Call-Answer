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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Sends the call-end event (FCM `CALL_ENDED` and the live `call_ended` event). Carries identifiers only. */
fun interface CallEndNotifier {
    suspend fun callEnded(userId: UserId, callId: String, outcome: Outcome, durationSec: Int)
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
        try {
            withTimeoutOrNull(notifyTimeoutMs) { notifier.callEnded(userId, s.id, entry.outcome, entry.durationSec) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The end event is best effort.
        }
        return FinalizeResult(entry, stored, mismatches)
    }

    private companion object {
        val TRACE_JSON = Json { encodeDefaults = true }
    }
}
