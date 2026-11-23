package com.callanswer.agent.call

import com.callanswer.agent.Clock
import com.callanswer.agent.SystemClock
import com.callanswer.agent.audio.StreamFormat
import com.callanswer.agent.exotel.StreamCall
import com.callanswer.agent.exotel.StreamCallFactory
import com.callanswer.agent.exotel.StreamEnd
import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.obs.TraceEventName
import com.callanswer.agent.sonic.RenewalConfig
import com.callanswer.agent.sonic.ServerState
import com.callanswer.agent.sonic.SessionIds
import com.callanswer.agent.sonic.SessionRenewer
import com.callanswer.agent.sonic.SonicEvent
import com.callanswer.agent.sonic.SonicSession
import com.callanswer.agent.sonic.SonicStreamFactory
import com.callanswer.agent.sonic.SonicToolRunner
import com.callanswer.agent.sonic.SonicVoices
import com.callanswer.agent.sonic.SystemPrompt
import com.callanswer.agent.store.CostCounter
import com.callanswer.agent.store.RecordingStore
import com.callanswer.agent.store.SettingsStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.engine.CallSession
import com.callanswer.core.engine.ConversationState
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.Language
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.Utterance
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.tenant.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Production [StreamCallFactory]: freezes the User's settings, picks the voice, opens a Sonic conversation through
 * a [SessionRenewer] and finalizes the call (call log, trace, cost) with [CallFinalizer] when the stream ends.
 *
 * Agent tools are not wired yet; the default [tools] answers every call with an error so the model carries on.
 */
class SonicStreamCallFactory(
    private val settings: SettingsStore,
    private val repoFor: (UserId) -> UserScopedRepository,
    private val finalizerFor: (UserId, UserScopedRepository) -> CallFinalizer,
    private val sonic: SonicStreamFactory,
    private val scope: CoroutineScope,
    private val renewal: RenewalConfig = RenewalConfig(),
    private val clock: Clock = SystemClock,
    private val tools: SonicToolRunner = SonicToolRunner { _, _ ->
        buildJsonObject {
            put("status", JsonPrimitive("ERROR"))
            put("error", JsonPrimitive("TOOL_UNAVAILABLE"))
        }
    },
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    private val newCallId: () -> String = { UUID.randomUUID().toString() },
    private val finalizeTimeoutMs: Long = FINALIZE_TIMEOUT_MS,
) : StreamCallFactory {

    override suspend fun create(userId: UserId, callSid: String, from: String?, to: String?, format: StreamFormat): StreamCall? {
        val saved = settings.get(userId)
        val doc = saved.document
        val frozen = doc.freeze()
        val voice = SonicVoices.resolve(frozen.voiceGender, doc.defaultLanguage, doc.voiceId)
            ?: SonicVoices.resolve(VoiceGender.FEMALE, Language.EN)
            ?: return null
        val callId = newCallId()
        val startedMs = clock.nowMillis()
        val trace = CallTrace(userId.sub, callId, clock)
        trace.record(TraceEventName.VOICE_STREAM_START, "exotel")
        val session = CallSession(callId, from?.trim()?.takeIf { it.isNotEmpty() }, Instant.ofEpochMilli(startedMs), zone, frozen)
        val call = SonicStreamCall(
            session, trace, clock, voice.voiceId, saved.version, doc.costRates, doc.monthlyLimitInr,
            finalizerFor(userId, repoFor(userId)), finalizeTimeoutMs,
        )
        val prompt = SystemPrompt.build(voice.voiceId, doc.defaultLanguage, doc.greetingName.orEmpty())
        val renewer = SessionRenewer(
            scope = scope,
            config = renewal,
            voiceId = voice.voiceId,
            systemPrompt = prompt,
            newSession = { SonicSession(SessionIds(UUID.randomUUID().toString(), UUID.randomUUID().toString()), sonic, tools, scope) },
            transcript = call::transcript,
            serverState = { ServerState() },
            clockMs = clock::nowMillis,
            onEvent = call::record,
        )
        call.renewer = renewer
        if (!renewer.start()) {
            renewer.end()
            trace.recordError("sonic", "START_FAILED")
            return null
        }
        trace.record(TraceEventName.SONIC_SESSION_START, "sonic")
        return call
    }

    companion object {
        /** Requirement 1.4: finalization finishes within 5 seconds of the stream end. */
        const val FINALIZE_TIMEOUT_MS = 5_000L
    }
}

internal class SonicStreamCall(
    private val session: CallSession,
    private val trace: CallTrace,
    private val clock: Clock,
    private val voiceId: String,
    private val settingsVersion: Long,
    private val rates: com.callanswer.core.model.CostRates,
    private val monthlyLimitInr: Int?,
    private val finalizer: CallFinalizer,
    private val finalizeTimeoutMs: Long,
) : StreamCall {
    lateinit var renewer: SessionRenewer
    override val events: Flow<SonicEvent> get() = renewer.events

    private val startMs = session.startedAt.toEpochMilli()
    private val utterances = ArrayList<Utterance>()
    private val callerBytes = AtomicLong()
    private val agentBytes = AtomicLong()
    private val firstCaller = AtomicBoolean(true)
    private val firstAgent = AtomicBoolean(true)
    private val ended = AtomicBoolean(false)

    /** Result of the finalization, for tests; null until [end] ran or when it timed out. */
    @Volatile var result: FinalizeResult? = null
        private set

    fun transcript(): List<Utterance> = synchronized(utterances) { utterances.toList() }

    fun record(e: SonicEvent) {
        val now = clock.nowMillis() - startMs
        when (e) {
            is SonicEvent.CallerTranscript -> add(Speaker.CALLER, e.text, now)
            is SonicEvent.AgentTranscript -> add(Speaker.AGENT, e.text, now)
            is SonicEvent.AgentAudio -> {
                agentBytes.addAndGet(e.pcm.size.toLong())
                if (firstAgent.compareAndSet(true, false)) trace.record(TraceEventName.FIRST_AGENT_AUDIO, "sonic")
            }
            is SonicEvent.Interrupted -> trace.record(TraceEventName.BARGE_IN, "sonic")
            else -> Unit
        }
    }

    private fun add(speaker: Speaker, text: String, now: Long) {
        if (text.isBlank()) return
        synchronized(utterances) { utterances.add(Utterance(speaker, text, null, now, now)) }
    }

    override fun callerAudio(pcm16k: ByteArray) {
        callerBytes.addAndGet(pcm16k.size.toLong())
        if (firstCaller.compareAndSet(true, false)) trace.record(TraceEventName.FIRST_CALLER_AUDIO, "exotel")
        renewer.sendAudio(pcm16k)
    }

    override fun onMark(name: String) = Unit

    override fun onDtmf(digit: String) = Unit

    override suspend fun end(reason: StreamEnd) {
        if (!ended.compareAndSet(false, true)) return
        renewer.end()
        val (outcome, endReason) = when (reason) {
            StreamEnd.CALLER_HUNG_UP -> Outcome.CALLER_HUNG_UP to EndReason.CALLER_HUNG_UP
            StreamEnd.AUDIO_INTERRUPTED -> Outcome.INTERRUPTED to EndReason.AUDIO_INTERRUPTED
        }
        val endedAtMs = clock.nowMillis() - startMs
        val input = FinalizeInput(
            session = session,
            state = ConversationState(transcript = transcript()),
            trace = trace,
            endedAtMs = endedAtMs,
            outcome = outcome,
            endReason = endReason,
            rates = rates,
            monthlyLimitInr = monthlyLimitInr,
            callerAudioSec = callerBytes.get() / BYTES_PER_SEC_16K,
            agentAudioSec = agentBytes.get() / BYTES_PER_SEC_24K,
            voiceId = voiceId,
            settingsVersion = settingsVersion,
        )
        result = withTimeoutOrNull(finalizeTimeoutMs) { finalizer.finalize(input) }
    }

    private companion object {
        const val BYTES_PER_SEC_16K = 32_000L
        const val BYTES_PER_SEC_24K = 48_000L
    }
}
