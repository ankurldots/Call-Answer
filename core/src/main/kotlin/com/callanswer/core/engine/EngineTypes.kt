package com.callanswer.core.engine

import com.callanswer.core.model.AlertReason
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.GateStatement
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.InterruptionRecord
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.model.LogNoteKind
import com.callanswer.core.model.NotificationKind
import com.callanswer.core.model.TakeoverRecord
import com.callanswer.core.model.TransferOutcome
import com.callanswer.core.model.TransferPath
import com.callanswer.core.model.Utterance
import java.time.Instant
import java.time.ZoneId

enum class Phase { ANSWERING, GREETING, LISTENING, DELIVERY, TRANSFER, AWAITING_TAKEOVER, TRANSFERRED, STALLING, CLARIFYING, ENDED }

enum class Mode { AGENT_ACTIVE, AGENT_MUTED, TAKE_OVER }

/** Where the User started a Take_Over. The reducer does not use it (Requirement 13.21). */
enum class TakeoverOrigin { ALERT, LIVE_CALL_SCREEN }

/** Result of a Path A transfer attempt. */
enum class PathAResult { MERGED, NO_ANSWER_30S, FAILED }

/** Who ended the call. */
enum class EndedBy { CALLER, USER, APP }

data class CallSession(
    val id: String,
    val callerNumber: String?,
    val startedAt: Instant,
    val zone: ZoneId,
    val frozen: FrozenSettings,
)

data class TransferState(
    val path: TransferPath,
    val startedAtMs: Long,
    val alertReason: AlertReason? = null,
    val alertRaisedAtMs: Long? = null,
    val lastPleaseWaitAtMs: Long? = null,
    val nobodyAvailablePending: Boolean = false,
    val outcome: TransferOutcome? = null,
)

data class ConversationState(
    val phase: Phase = Phase.ANSWERING,
    val mode: Mode = Mode.AGENT_ACTIVE,
    val intent: IntentCategory = IntentCategory.UNKNOWN,
    val lastLanguage: Language? = null,
    val clarifyPrompts: Int = 0,
    val greetingRepeated: Boolean = false,
    val silencePrompts: Int = 0,
    val repeatsUsed: Int = 0,
    val garbledAsks: Int = 0,
    val stallStartMs: Long? = null,
    val takeoverStartMs: Long? = null,
    val takeovers: List<TakeoverRecord> = emptyList(),
    val gateStatements: List<GateStatement> = emptyList(),
    val spokenResponseIds: Set<String> = emptySet(),
    val resumePhase: Phase? = null,
    /** Id of the latest delivery directions response, and whether it has been spoken completely (3.6). */
    val directionsResponseId: String? = null,
    val directionsDone: Boolean = false,
    /** The Location URL was texted to the Caller in this call (it is texted at most once). */
    val locationSent: Boolean = false,
    /** The Caller asked for the location but it could not be texted (no URL set or number unknown). */
    val locationWanted: Boolean = false,
    val transfer: TransferState? = null,
    /** Id of the spoken TransferNotice; its `AgentSpeechFinished` starts the transfer (4.1). */
    val transferNoticeResponseId: String? = null,
    /** Set when the call ended for lack of usable audio (8.8). */
    val interruption: InterruptionRecord? = null,
    val transcript: List<Utterance> = emptyList(),
)

sealed interface ConversationEvent {
    val atMs: Long

    data class CallAnswered(override val atMs: Long) : ConversationEvent
    data class CallerUtterance(
        override val atMs: Long,
        val text: String,
        val asrLanguage: Language?,
        val localTime: LocalTimeHm?,
    ) : ConversationEvent
    data class CallerAudioUnusable(override val atMs: Long) : ConversationEvent
    data class Tick(override val atMs: Long, val silentForMs: Long, val noAudioForMs: Long) : ConversationEvent
    data class AgentSpeechFinished(override val atMs: Long, val responseId: String) : ConversationEvent
    data class VoiceActivity(override val atMs: Long) : ConversationEvent
    data class UserTakeOver(override val atMs: Long, val origin: TakeoverOrigin) : ConversationEvent
    data class UserMute(override val atMs: Long) : ConversationEvent
    data class UserResume(override val atMs: Long) : ConversationEvent
    data class UserEndCall(override val atMs: Long) : ConversationEvent
    data class QuickReplySent(override val atMs: Long, val text: String) : ConversationEvent
    data class TransferResult(override val atMs: Long, val result: PathAResult) : ConversationEvent
    data class ResponseFailed(override val atMs: Long, val attempt: Int) : ConversationEvent
    data class SpeechSynthesisFailed(override val atMs: Long, val attempt: Int) : ConversationEvent
    data class CallEnded(override val atMs: Long, val by: EndedBy) : ConversationEvent
}

sealed interface SpeechSpec {
    data object Greeting : SpeechSpec
    data object Disclosure : SpeechSpec

    /** [proactiveGate] true: the gate statement comes first (3.10, 11.8). False: steps only (gate already answered). */
    data class DeliveryDirections(val repetition: Int, val proactiveGate: Boolean = true) : SpeechSpec

    /** [fictitious] true: invented gate details for a non-delivery Caller (11.11). */
    data class GateInfo(val inResponseToQuery: Boolean, val fictitious: Boolean = false) : SpeechSpec
    /** The Location URL is being texted. [addressConfirmed] is true when the Caller stated an address that matched. */
    data class LocationSmsSent(val addressConfirmed: Boolean) : SpeechSpec
    /** The address the Caller stated does not match the configured one. Nothing is sent. */
    data object AddressNotMatching : SpeechSpec
    data object AskReason : SpeechSpec
    data object AskRepeatRequest : SpeechSpec
    data object TransferNotice : SpeechSpec
    data object PleaseWait : SpeechSpec
    data object NobodyAvailable : SpeechSpec
    data object WillReturnCall : SpeechSpec
    data object NoFurtherRepetitions : SpeechSpec
    data class Stall(val callerText: String) : SpeechSpec
    data object StallFiller : SpeechSpec
    data object SilencePrompt : SpeechSpec
    data object BridgingPhrase : SpeechSpec
    data class QuickReply(val text: String) : SpeechSpec
    data object FallbackPhrase : SpeechSpec
    data object PoliteGoodbye : SpeechSpec
    /** Said once before hanging up on an abusive Caller. */
    data object AbuseGoodbye : SpeechSpec
}

sealed interface Effect {
    data class Speak(val responseId: String, val spec: SpeechSpec, val language: Language) : Effect
    data object StopSpeaking : Effect
    data class StartTransfer(val target: String, val callerNumber: String?) : Effect
    data class RaiseTakeoverAlert(val callerNumber: String?, val reason: AlertReason) : Effect
    data object ClearTakeoverAlert : Effect
    data class SendMapSms(val number: String) : Effect
    data class Notify(val kind: NotificationKind) : Effect
    data class EndCall(val reason: EndReason) : Effect
    data class LogNote(val note: LogNoteKind) : Effect
}

interface ConversationEngine {
    fun reduce(state: ConversationState, event: ConversationEvent, ctx: EngineContext): Transition
}

data class Transition(val state: ConversationState, val effects: List<Effect>)

/**
 * [callerNumber] is the number carried in `SendMapSms` (the Location URL text); it is only used when [callerKnownNumber] is true.
 * [callStartEpochMs] and [zoneId] turn the event clock (`atMs`) into the epoch time and zone stored in a `TakeoverRecord`.
 */
data class EngineContext(
    val frozen: FrozenSettings,
    val callerKnownNumber: Boolean,
    val callerNumber: String? = null,
    val callStartEpochMs: Long = 0L,
    val zoneId: String = "UTC",
)
