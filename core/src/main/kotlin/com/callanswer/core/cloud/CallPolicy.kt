package com.callanswer.core.cloud

/**
 * Pure call policy reducer: `step(state, event, now) -> (state, effects)`.
 * All limits and counters of Requirements 6, 12 and 13 are enforced here, not by the model.
 * Time values are epoch milliseconds. No clock, I/O, or randomness is used.
 *
 * Silence interpretation: outside STALLING the first Silence_Timeout prompts and the second ends the call
 * (the greeting is repeated once instead of a prompt); in STALLING the first two prompt and the third ends.
 */
object CallPolicy {
    const val MAX_CALL_MS = 15 * 60_000L
    const val HARD_LIMIT_MS = 60 * 60_000L
    const val CLOSE_GRACE_MS = 10_000L
    /** "Please wait" interval while the Takeover_Alert waits (Requirement 11.3). */
    const val PLEASE_WAIT_INTERVAL_MS = 15_000L
    /** After this time without a take over the Agent says nobody is available and stays with the Caller (11.6). */
    const val NOBODY_AVAILABLE_MS = 30_000L
    /** Dial timeout of the Bridge_Call (Connect applet, shorter than the carrier ring time). */
    const val BRIDGE_DIAL_TIMEOUT_MS = 15_000L
    const val MAX_DIRECTIONS_REPETITIONS = 3
    const val MAX_CLARIFY_PROMPTS = 2
    const val MAX_GARBLED_REPEATS = 3
    const val STALLING_SILENCE_LIMIT = 3
    const val DEFAULT_SILENCE_TIMEOUT_MS = 8_000L
    const val DEFAULT_MAX_STALLING_MINUTES = 5

    const val TOOL_DIRECTIONS = "get_delivery_directions"

    enum class Phase { GREETING, LISTENING, DELIVERY, WAITING_TAKEOVER, STALLING, UNKNOWN_CLARIFY, CLOSING, TAKEOVER, ENDED }
    enum class Intent { DELIVERY, DOOR_NOT_OPENED, SOLICITATION, UNKNOWN }
    enum class TimerKind { SILENCE, MAX_CALL, HARD_LIMIT, STALLING_MAX, CLOSE_GRACE, TAKEOVER_WAIT, TAKEOVER_NOBODY, BRIDGE_DIAL }
    enum class EndReason {
        CALLER_SILENCE, MAX_DURATION, MAX_STALLING, HARD_LIMIT, GARBLED, DIRECTIONS_LIMIT,
        UNKNOWN_MESSAGE, CALLER_HUNG_UP, USER_ENDED,
    }

    data class Config(
        val silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS,
        val maxStallingMinutes: Int = DEFAULT_MAX_STALLING_MINUTES,
    ) {
        val maxStallingMs: Long get() = maxStallingMinutes.coerceIn(1, 60) * 60_000L
    }

    data class State(
        val config: Config = Config(),
        val phase: Phase = Phase.GREETING,
        val startedAt: Long? = null,
        val stallingStartedAt: Long? = null,
        val intent: Intent? = null,
        val greetingRepeats: Int = 0,
        val directionsRepetitions: Int = 0,
        val directionsGiven: Boolean = false,
        val silencePrompts: Int = 0,
        val clarifyPrompts: Int = 0,
        val garbledRepeats: Int = 0,
        val messageRequested: Boolean = false,
        val endReason: EndReason? = null,
        /** True from the User's tap until the Bridge_Call connects or fails; a second tap is ignored meanwhile. */
        val bridgeInProgress: Boolean = false,
        val bridgeConnected: Boolean = false,
        val bridgeFailures: Int = 0,
        val nobodyAnnounced: Boolean = false,
    )

    sealed interface Event {
        data object StreamStarted : Event
        data object SonicReady : Event
        data class CallerUtterance(val text: String?) : Event
        data object AgentAudioDone : Event
        data object SilenceElapsed : Event
        data class ToolCalled(val tool: String, val args: Map<String, String> = emptyMap()) : Event
        data class IntentLogged(val intent: Intent) : Event
        data object TakeoverRequested : Event
        data object UserTookOver : Event
        data object BridgeFailed : Event
        data object BridgeConnected : Event
        data class TimerFired(val kind: TimerKind) : Event
        data object StreamStopped : Event
        data object SonicFailed : Event
        data object UserEndedCall : Event
        data class QuickReply(val text: String) : Event
        data object PiiHit : Event
    }

    sealed interface Effect {
        data class Say(val text: String) : Effect
        data class Inject(val instruction: String) : Effect
        data object ClearAudio : Effect
        data class StartTimer(val kind: TimerKind, val delayMs: Long) : Effect
        data class CancelTimer(val kind: TimerKind) : Effect
        data class SendAlert(val kind: String) : Effect
        data class CloseStream(val reason: EndReason) : Effect
        data object OpenReplacementSession : Effect
        data class ApplyFallback(val cause: String) : Effect
        data class Record(val note: String) : Effect
        /** Closes the Sonic_Session when the Bridge_Call connected; no replacement is opened afterwards. */
        data object CloseSonicSession : Effect
    }

    data class Result(val state: State, val effects: List<Effect>)

    fun initial(config: Config = Config()): State = State(config = config)

    fun step(state: State, event: Event, now: Long): Result {
        if (state.phase == Phase.ENDED) return Result(state, emptyList())
        if (event == Event.StreamStarted && state.startedAt == null) {
            return Result(
                state.copy(startedAt = now, phase = Phase.GREETING),
                listOf(
                    Effect.StartTimer(TimerKind.MAX_CALL, MAX_CALL_MS),
                    Effect.StartTimer(TimerKind.HARD_LIMIT, HARD_LIMIT_MS),
                ),
            )
        }
        val s = state
        // Caller or user hang-up and fatal Sonic failure take priority over everything else.
        when (event) {
            Event.StreamStopped -> return finish(s, EndReason.CALLER_HUNG_UP, now, closeStream = false)
            Event.UserEndedCall -> return finish(s, EndReason.USER_ENDED, now, closeStream = true)
            else -> Unit
        }
        if (s.phase != Phase.CLOSING) limitReason(s, now)?.let { return beginClosing(s, it, now) }
        return when (event) {
            Event.StreamStarted, Event.StreamStopped, Event.UserEndedCall -> Result(s, emptyList())
            Event.SonicReady -> Result(s, listOf(Effect.Inject("GREET")))
            is Event.CallerUtterance -> onUtterance(s, event.text, now)
            Event.AgentAudioDone -> onAudioDone(s, now)
            Event.SilenceElapsed -> onSilence(s, now)
            is Event.TimerFired -> onTimer(s, event.kind, now)
            is Event.ToolCalled -> onTool(s, event, now)
            is Event.IntentLogged -> onIntent(s, event.intent, now)
            Event.TakeoverRequested -> onTakeoverRequested(s)
            Event.UserTookOver -> onUserTookOver(s)
            Event.BridgeConnected -> onBridgeConnected(s)
            Event.BridgeFailed -> onBridgeFailed(s)
            Event.SonicFailed ->
                if (s.phase == Phase.TAKEOVER || s.phase == Phase.CLOSING) Result(s, emptyList())
                else Result(s, listOf(Effect.OpenReplacementSession))
            is Event.QuickReply ->
                if (s.phase == Phase.TAKEOVER || s.phase == Phase.CLOSING) Result(s, emptyList())
                else Result(s, listOf(Effect.Say(event.text)))
            Event.PiiHit -> Result(
                s,
                listOf(
                    Effect.ClearAudio,
                    Effect.Inject("DO_NOT_DISCLOSE"),
                    Effect.SendAlert("PII_HIT"),
                    Effect.Record("PII_HIT"),
                ),
            )
        }
    }

    private val takeoverTimers = listOf(
        Effect.CancelTimer(TimerKind.TAKEOVER_WAIT),
        Effect.CancelTimer(TimerKind.TAKEOVER_NOBODY),
    )

    /** One Take_Over request at a time: ignored while waiting, while a Bridge_Call runs, in TAKEOVER and while closing. */
    private fun onTakeoverRequested(s: State): Result = when {
        s.phase == Phase.TAKEOVER || s.phase == Phase.WAITING_TAKEOVER || s.phase == Phase.CLOSING || s.bridgeInProgress ->
            Result(s, emptyList())
        else -> Result(
            s.copy(phase = Phase.WAITING_TAKEOVER, nobodyAnnounced = false),
            listOf(
                Effect.SendAlert("TAKEOVER_REQUEST"),
                Effect.StartTimer(TimerKind.TAKEOVER_WAIT, PLEASE_WAIT_INTERVAL_MS),
                Effect.StartTimer(TimerKind.TAKEOVER_NOBODY, NOBODY_AVAILABLE_MS),
            ),
        )
    }

    /** The User tapped "Take over": stop the Agent audio and start one Bridge_Call. A second tap changes nothing. */
    private fun onUserTookOver(s: State): Result {
        if (s.phase == Phase.CLOSING || s.phase == Phase.TAKEOVER || s.bridgeInProgress) return Result(s, emptyList())
        return Result(
            s.copy(phase = Phase.TAKEOVER, bridgeInProgress = true),
            takeoverTimers + listOf(
                Effect.CancelTimer(TimerKind.SILENCE),
                Effect.CancelTimer(TimerKind.STALLING_MAX),
                Effect.ClearAudio,
                Effect.Record("TAKEOVER_STARTED"),
                Effect.Inject("CONNECTING"),
                Effect.StartTimer(TimerKind.BRIDGE_DIAL, BRIDGE_DIAL_TIMEOUT_MS),
            ),
        )
    }

    private fun onBridgeConnected(s: State): Result {
        if (s.phase == Phase.CLOSING || s.bridgeConnected) return Result(s, emptyList())
        val start = if (s.phase == Phase.TAKEOVER) emptyList() else listOf(Effect.Record("TAKEOVER_STARTED"))
        return Result(
            s.copy(phase = Phase.TAKEOVER, bridgeInProgress = false, bridgeConnected = true),
            takeoverTimers + start + listOf(
                Effect.CancelTimer(TimerKind.SILENCE),
                Effect.CancelTimer(TimerKind.STALLING_MAX),
                Effect.CancelTimer(TimerKind.BRIDGE_DIAL),
                Effect.CloseSonicSession,
            ),
        )
    }

    /** A failed Bridge_Call returns the Caller to the Agent and records the failure. */
    private fun onBridgeFailed(s: State): Result {
        if (!s.bridgeInProgress || s.phase == Phase.CLOSING) return Result(s, emptyList())
        return Result(
            s.copy(phase = Phase.LISTENING, bridgeInProgress = false, bridgeFailures = s.bridgeFailures + 1, silencePrompts = 0),
            listOf(
                Effect.CancelTimer(TimerKind.BRIDGE_DIAL),
                Effect.Record("BRIDGE_FAILED"),
                Effect.Inject("PERSON_UNREACHABLE"),
            ),
        )
    }

    private fun onTakeoverTimer(s: State, kind: TimerKind): Result = when (kind) {
        TimerKind.TAKEOVER_WAIT ->
            if (s.phase == Phase.WAITING_TAKEOVER) Result(
                s,
                listOf(Effect.Inject("PLEASE_WAIT"), Effect.StartTimer(TimerKind.TAKEOVER_WAIT, PLEASE_WAIT_INTERVAL_MS)),
            ) else Result(s, emptyList())
        TimerKind.TAKEOVER_NOBODY ->
            if (s.phase == Phase.WAITING_TAKEOVER) Result(
                s.copy(phase = Phase.LISTENING, nobodyAnnounced = true),
                listOf(
                    Effect.CancelTimer(TimerKind.TAKEOVER_WAIT),
                    Effect.Record("TAKEOVER_NOBODY_AVAILABLE"),
                    Effect.Inject("NOBODY_AVAILABLE"),
                ),
            ) else Result(s, emptyList())
        else -> if (s.bridgeInProgress) onBridgeFailed(s) else Result(s, emptyList()) // BRIDGE_DIAL
    }

    private fun limitReason(s: State, now: Long): EndReason? {
        val started = s.startedAt ?: return null
        if (now - started >= HARD_LIMIT_MS) return EndReason.HARD_LIMIT
        if (s.phase == Phase.TAKEOVER) return null
        if (s.phase == Phase.STALLING) {
            val st = s.stallingStartedAt ?: started
            return if (now - st >= s.config.maxStallingMs) EndReason.MAX_STALLING else null
        }
        return if (now - started >= MAX_CALL_MS) EndReason.MAX_DURATION else null
    }

    private fun stallingNote(s: State, now: Long): List<Effect> =
        s.stallingStartedAt?.let { listOf(Effect.Record("STALLING_SECONDS=${(now - it) / 1000}")) } ?: emptyList()

    /** Polite close: the agent speaks a farewell, the stream closes after it played or after the grace timer. */
    private fun beginClosing(s: State, reason: EndReason, now: Long, extra: List<Effect> = emptyList()): Result =
        Result(
            s.copy(phase = Phase.CLOSING, endReason = reason),
            extra + listOf(
                Effect.CancelTimer(TimerKind.SILENCE),
                Effect.CancelTimer(TimerKind.STALLING_MAX),
                Effect.Record("END_REASON=${reason.name}"),
                Effect.Inject("FAREWELL:${reason.name}"),
                Effect.StartTimer(TimerKind.CLOSE_GRACE, CLOSE_GRACE_MS),
            ) + stallingNote(s, now),
        )

    private fun finish(s: State, reason: EndReason, now: Long, closeStream: Boolean): Result =
        Result(
            s.copy(phase = Phase.ENDED, endReason = s.endReason ?: reason),
            listOf(
                Effect.CancelTimer(TimerKind.SILENCE),
                Effect.CancelTimer(TimerKind.STALLING_MAX),
                Effect.CancelTimer(TimerKind.MAX_CALL),
                Effect.CancelTimer(TimerKind.HARD_LIMIT),
                Effect.CancelTimer(TimerKind.CLOSE_GRACE),
            ) + stallingNote(s, now) + (if (closeStream) listOf(Effect.CloseStream(s.endReason ?: reason)) else emptyList()),
        )

    private fun onUtterance(s: State, text: String?, now: Long): Result {
        if (s.phase == Phase.TAKEOVER || s.phase == Phase.CLOSING) return Result(s, emptyList())
        val cancel = Effect.CancelTimer(TimerKind.SILENCE)
        if (text.isNullOrBlank()) {
            if (s.garbledRepeats >= MAX_GARBLED_REPEATS) return beginClosing(s, EndReason.GARBLED, now, listOf(cancel))
            return Result(
                s.copy(garbledRepeats = s.garbledRepeats + 1),
                listOf(cancel, Effect.Inject("ASK_REPEAT")),
            )
        }
        if (s.messageRequested) {
            return beginClosing(s, EndReason.UNKNOWN_MESSAGE, now, listOf(cancel, Effect.Record("MESSAGE:$text")))
        }
        val phase = if (s.phase == Phase.GREETING) Phase.LISTENING else s.phase
        return Result(s.copy(phase = phase, garbledRepeats = 0, silencePrompts = 0), listOf(cancel))
    }

    private fun onAudioDone(s: State, now: Long): Result = when (s.phase) {
        Phase.CLOSING -> finish(s, s.endReason ?: EndReason.CALLER_SILENCE, now, closeStream = true)
        Phase.TAKEOVER, Phase.ENDED, Phase.WAITING_TAKEOVER -> Result(s, emptyList())
        else -> Result(s, listOf(Effect.StartTimer(TimerKind.SILENCE, s.config.silenceTimeoutMs)))
    }

    private fun onTimer(s: State, kind: TimerKind, now: Long): Result = when (kind) {
        TimerKind.SILENCE -> onSilence(s, now)
        TimerKind.CLOSE_GRACE ->
            if (s.phase == Phase.CLOSING) finish(s, s.endReason ?: EndReason.MAX_DURATION, now, closeStream = true)
            else Result(s, emptyList())
        // Limit timers: the limit check ran before dispatch and already closed the call when due.
        TimerKind.MAX_CALL, TimerKind.HARD_LIMIT, TimerKind.STALLING_MAX -> Result(s, emptyList())
        TimerKind.TAKEOVER_WAIT, TimerKind.TAKEOVER_NOBODY, TimerKind.BRIDGE_DIAL -> onTakeoverTimer(s, kind)
    }

    private fun onSilence(s: State, now: Long): Result {
        when (s.phase) {
            Phase.TAKEOVER, Phase.WAITING_TAKEOVER, Phase.CLOSING, Phase.ENDED -> return Result(s, emptyList())
            else -> Unit
        }
        val alert = listOf(Effect.SendAlert("CALLER_SILENT"))
        if (s.phase == Phase.GREETING) {
            return if (s.greetingRepeats == 0) {
                Result(s.copy(greetingRepeats = 1), listOf(Effect.Inject("REPEAT_GREETING")))
            } else beginClosing(s, EndReason.CALLER_SILENCE, now, alert)
        }
        if (s.phase == Phase.STALLING) {
            val n = s.silencePrompts + 1
            return if (n >= STALLING_SILENCE_LIMIT) beginClosing(s.copy(silencePrompts = n), EndReason.CALLER_SILENCE, now, alert)
            else Result(s.copy(silencePrompts = n), listOf(Effect.Inject("PROMPT_CONTINUE")))
        }
        return if (s.silencePrompts == 0) Result(s.copy(silencePrompts = 1), listOf(Effect.Inject("PROMPT_SILENCE")))
        else beginClosing(s.copy(silencePrompts = s.silencePrompts + 1), EndReason.CALLER_SILENCE, now, alert)
    }

    private fun onTool(s: State, e: Event.ToolCalled, now: Long): Result {
        // A Gate_Query (get_gate_info) and any other tool never touches the clarification counter.
        if (e.tool != TOOL_DIRECTIONS || s.phase == Phase.CLOSING || s.phase == Phase.TAKEOVER) return Result(s, emptyList())
        if (!s.directionsGiven) return Result(s.copy(directionsGiven = true), emptyList())
        if (s.directionsRepetitions >= MAX_DIRECTIONS_REPETITIONS) {
            return beginClosing(s, EndReason.DIRECTIONS_LIMIT, now, listOf(Effect.Inject("NO_MORE_REPETITIONS")))
        }
        return Result(s.copy(directionsRepetitions = s.directionsRepetitions + 1), emptyList())
    }

    private fun onIntent(s: State, intent: Intent, now: Long): Result {
        if (s.phase == Phase.CLOSING || s.phase == Phase.TAKEOVER) return Result(s, emptyList())
        if (s.phase == Phase.STALLING) return Result(s, emptyList())
        if (s.phase == Phase.WAITING_TAKEOVER) return Result(s.copy(intent = intent), emptyList())
        val base = s.copy(intent = intent)
        return when (intent) {
            Intent.DELIVERY -> Result(base.copy(phase = Phase.DELIVERY), emptyList())
            Intent.DOOR_NOT_OPENED -> Result(base.copy(phase = Phase.LISTENING), emptyList())
            Intent.SOLICITATION -> Result(
                base.copy(phase = Phase.STALLING, stallingStartedAt = now, silencePrompts = 0),
                listOf(Effect.StartTimer(TimerKind.STALLING_MAX, s.config.maxStallingMs), Effect.Inject("STALL")),
            )
            Intent.UNKNOWN ->
                if (s.clarifyPrompts < MAX_CLARIFY_PROMPTS) Result(
                    base.copy(phase = Phase.UNKNOWN_CLARIFY, clarifyPrompts = s.clarifyPrompts + 1),
                    listOf(Effect.Inject("ASK_REASON")),
                ) else Result(
                    base.copy(phase = Phase.UNKNOWN_CLARIFY, messageRequested = true),
                    listOf(Effect.Inject("ASK_MESSAGE")),
                )
        }
    }
}
