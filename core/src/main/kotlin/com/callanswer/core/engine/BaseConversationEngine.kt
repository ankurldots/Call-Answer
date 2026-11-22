package com.callanswer.core.engine

import com.callanswer.core.gate.GateSelection
import com.callanswer.core.gate.selectGate
import com.callanswer.core.intent.IntentClassifier
import com.callanswer.core.intent.PhraseTables
import com.callanswer.core.intent.RuleIntentClassifier
import com.callanswer.core.language.LanguageDetector
import com.callanswer.core.language.ResponseLanguageResolver
import com.callanswer.core.language.ScriptLanguageDetector
import com.callanswer.core.address.AddressMatcher
import com.callanswer.core.model.AlertReason
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.GateStatement
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.InterruptionRecord
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.model.LogNoteKind
import com.callanswer.core.model.NotificationKind
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.TakeoverRecord
import com.callanswer.core.model.TransferOutcome
import com.callanswer.core.model.TransferPath
import com.callanswer.core.model.Utterance

/**
 * Pure reducer for the call conversation. This is the base: greeting, disclosure, utterance
 * classification, language resolution, dispatch and the transcript. Later tasks add rules per phase
 * (delivery, stalling and silence, transfer, override modes) in the matching `on*` handler or in a
 * new `reduce<Phase>` function called from [onEvent], without changing the code below.
 *
 * The only non-determinism is [ids], which must hand out a fresh id per call (Requirement 8.5).
 */
class BaseConversationEngine(
    private val ids: ResponseIdGenerator = SequentialResponseIdGenerator(),
    private val classifier: IntentClassifier = RuleIntentClassifier(),
    private val detector: LanguageDetector = ScriptLanguageDetector.withBundledLexicon(),
    private val phrases: PhraseTables = PhraseTables.DEFAULT,
) : ConversationEngine {

    override fun reduce(state: ConversationState, event: ConversationEvent, ctx: EngineContext): Transition =
        onEvent(state, event, ctx)

    private fun onEvent(state: ConversationState, event: ConversationEvent, ctx: EngineContext): Transition {
        if (state.phase == Phase.ENDED) return Transition(state, emptyList())
        return when (event) {
            is ConversationEvent.UserTakeOver -> onTakeOver(state, event)
            is ConversationEvent.UserMute -> onMute(state)
            is ConversationEvent.UserResume -> onResume(state, event, ctx)
            is ConversationEvent.UserEndCall -> Transition(
                closeTakeover(state.copy(phase = Phase.ENDED), event.atMs, ctx),
                listOf(Effect.EndCall(EndReason.USER_ENDED)),
            )
            is ConversationEvent.QuickReplySent -> onQuickReply(state, event)
            // 13.7: voice activity never stops the Agent.
            is ConversationEvent.VoiceActivity -> Transition(state, emptyList())
            is ConversationEvent.CallEnded -> onCallEnded(state, event, ctx).let { it.copy(state = closeTakeover(it.state, event.atMs, ctx)) }
            is ConversationEvent.TransferResult -> onTransferResult(state, event, ctx)
            else -> if (state.mode == Mode.TAKE_OVER) onTakeOverEvent(state, event, ctx) else onAgentEvent(state, event, ctx)
        }
    }

    private fun onAgentEvent(state: ConversationState, event: ConversationEvent, ctx: EngineContext): Transition {
        return when (event) {
            is ConversationEvent.CallAnswered -> onCallAnswered(state, event, ctx)
            is ConversationEvent.CallerUtterance -> onCallerUtterance(state, event, ctx)
            is ConversationEvent.AgentSpeechFinished -> onSpeechFinished(state, event, ctx)
            is ConversationEvent.Tick -> onTick(state, event, ctx)
            is ConversationEvent.CallerAudioUnusable -> onAudioUnusable(state)
            is ConversationEvent.ResponseFailed -> onResponseFailed(state, event)
            is ConversationEvent.SpeechSynthesisFailed -> onSynthesisFailed(state, event)
            // Handled by the delivery, stalling, transfer and override tasks.
            else -> Transition(state, emptyList())
        }
    }

    // ---- Override modes (4.8, 13.6 to 13.14, 13.18, 13.20, 13.21) ----

    /** The origin is ignored, so an alert take over equals a Live_Call_Screen take over (13.21). */
    private fun onTakeOver(state: ConversationState, event: ConversationEvent.UserTakeOver): Transition {
        if (state.mode == Mode.TAKE_OVER) return Transition(state, emptyList())
        val transfer = state.transfer
        val effects = mutableListOf<Effect>(Effect.StopSpeaking)
        val alertShown = transfer != null && transfer.alertRaisedAtMs != null && transfer.outcome != TransferOutcome.TAKEN_OVER_BY_USER
        if (alertShown) effects += Effect.ClearTakeoverAlert
        var updated = transfer
        if (transfer != null && (state.phase == Phase.AWAITING_TAKEOVER || state.phase == Phase.TRANSFER)) {
            effects += Effect.LogNote(LogNoteKind.TRANSFER_COMPLETED_BY_USER)
            updated = transfer.copy(outcome = TransferOutcome.TAKEN_OVER_BY_USER)
        }
        return Transition(state.copy(mode = Mode.TAKE_OVER, takeoverStartMs = event.atMs, transfer = updated), effects)
    }

    private fun onMute(state: ConversationState): Transition {
        if (state.mode != Mode.AGENT_ACTIVE) return Transition(state, emptyList())
        return Transition(state.copy(mode = Mode.AGENT_MUTED), listOf(Effect.StopSpeaking))
    }

    private fun onResume(state: ConversationState, event: ConversationEvent.UserResume, ctx: EngineContext): Transition {
        if (state.mode == Mode.AGENT_ACTIVE) return Transition(state, emptyList())
        val language = ResponseLanguageResolver.resolve(null, state.lastLanguage)
        val effects = mutableListOf<Effect>(speak(SpeechSpec.BridgingPhrase, language))
        var next = closeTakeover(state, event.atMs, ctx)
        val transfer = next.transfer
        if (transfer != null && transfer.nobodyAvailablePending) {
            effects += speak(SpeechSpec.NobodyAvailable, language)
            next = next.copy(transfer = transfer.copy(nobodyAvailablePending = false))
        }
        return Transition(next.copy(mode = Mode.AGENT_ACTIVE), effects)
    }

    /** Closes an open takeover period as a `TakeoverRecord` (13.12). */
    private fun closeTakeover(state: ConversationState, endMs: Long, ctx: EngineContext): ConversationState {
        val start = state.takeoverStartMs ?: return state
        val record = TakeoverRecord(
            startedAtEpochMs = ctx.callStartEpochMs + start,
            zoneId = ctx.zoneId,
            durationSec = ((endMs - start).coerceAtLeast(0L) / 1000L).toInt(),
        )
        return state.copy(takeoverStartMs = null, takeovers = state.takeovers + record)
    }

    /** 13.14: spoken in the Caller's language; ignored while muted or in Take_Over. */
    private fun onQuickReply(state: ConversationState, event: ConversationEvent.QuickReplySent): Transition {
        if (state.mode != Mode.AGENT_ACTIVE) return Transition(state, emptyList())
        val language = ResponseLanguageResolver.resolve(null, state.lastLanguage)
        return Transition(state, listOf(speak(SpeechSpec.QuickReply(event.text), language)))
    }

    /** In Take_Over the transcript grows (utterances labelled LIVE) and nothing else reacts (13.8, 13.9). */
    private fun onTakeOverEvent(state: ConversationState, event: ConversationEvent, ctx: EngineContext): Transition = when (event) {
        is ConversationEvent.CallerUtterance -> {
            val text = event.text.trim()
            if (text.isEmpty()) {
                Transition(state, emptyList())
            } else {
                val detected = detector.detect(text) ?: event.asrLanguage
                Transition(
                    state.copy(
                        lastLanguage = detected ?: state.lastLanguage,
                        transcript = state.transcript + Utterance(Speaker.LIVE, text, detected, event.atMs, event.atMs),
                    ),
                    emptyList(),
                )
            }
        }
        is ConversationEvent.AgentSpeechFinished -> onSpeechFinished(state, event, ctx)
        else -> Transition(state, emptyList())
    }

    // ---- CallAnswered (1.4, 10.11) ----

    private fun onCallAnswered(state: ConversationState, event: ConversationEvent.CallAnswered, ctx: EngineContext): Transition {
        if (state.phase != Phase.ANSWERING) return Transition(state, emptyList())
        if (state.mode != Mode.AGENT_ACTIVE) return Transition(state.copy(phase = Phase.GREETING), emptyList())
        val language = ResponseLanguageResolver.resolve(null, state.lastLanguage)
        val effects = mutableListOf<Effect>(speak(SpeechSpec.Greeting, language))
        if (ctx.frozen.disclosureEnabled) effects += speak(SpeechSpec.Disclosure, language)
        return Transition(state.copy(phase = Phase.GREETING), effects)
    }

    // ---- CallerUtterance (2.8, 2.10, 3.1, 4.1, 5.1, 6.1, 7.x, 11.x) ----

    private fun onCallerUtterance(state: ConversationState, event: ConversationEvent.CallerUtterance, ctx: EngineContext): Transition {
        val text = event.text.trim()
        val detected = if (text.isEmpty()) null else detector.detect(text) ?: event.asrLanguage
        val language = ResponseLanguageResolver.resolve(detected, state.lastLanguage)

        var next = state.copy(lastLanguage = detected ?: state.lastLanguage)
        if (text.isNotEmpty()) {
            // Any Caller utterance ends the consecutive silence and unusable-audio runs (5.6, 8.7).
            next = next.copy(
                transcript = next.transcript + Utterance(Speaker.CALLER, text, detected, event.atMs, event.atMs),
                silencePrompts = 0,
                garbledAsks = 0,
            )
        }
        if (!canDispatch(next)) return Transition(next, emptyList())

        if (text.isEmpty()) {
            return Transition(next, listOf(speak(SpeechSpec.AskRepeatRequest, language)))
        }

        val result = classifier.classify(text)
        // 2.8: a determined category replaces the current one; UNKNOWN keeps it.
        // A resolved utterance also ends the run of consecutive clarification prompts (6.2).
        if (result.category != IntentCategory.UNKNOWN) next = next.copy(intent = result.category, clarifyPrompts = 0)
        // An address says words such as "flat" that can look like a sales call, so a Caller who was already
        // known as delivery keeps that intent for this check.
        if (!result.gateQuery && (state.intent == IntentCategory.DELIVERY || result.category == IntentCategory.DELIVERY)) {
            locationRequest(next.copy(intent = IntentCategory.DELIVERY), text, event, language, ctx)?.let { return it }
        }
        if (next.phase == Phase.DELIVERY) {
            deliveryUtterance(next, text, result.category, result.gateQuery, event, language, ctx)?.let { return it }
        }
        return dispatch(next, result.category, result.gateQuery, event, language, ctx)
    }

    // ---- Delivery flow (3.3 to 3.6, 3.9 to 3.11, 10.9, 11.5 to 11.7, 11.17) ----

    /** Handles a Caller utterance while the directions are given. Null means: use the normal dispatch. */
    private fun deliveryUtterance(
        state: ConversationState,
        text: String,
        category: IntentCategory,
        gateQuery: Boolean,
        event: ConversationEvent.CallerUtterance,
        language: Language,
        ctx: EngineContext,
    ): Transition? {
        val normalized = text
        if (phrases.matchesAny(phrases.repeatRequest, normalized)) {
            if (state.repeatsUsed >= MAX_REPETITIONS) {
                // 3.9: the fourth request gets NoFurtherRepetitions and the call ends.
                return Transition(
                    state.copy(phase = Phase.ENDED),
                    listOf(speak(SpeechSpec.NoFurtherRepetitions, language), Effect.EndCall(EndReason.REPEATS_EXHAUSTED)),
                )
            }
            val repetition = state.repeatsUsed + 1
            return speakDirections(state.copy(repeatsUsed = repetition), repetition, proactive = true, event, language, ctx)
        }
        if (gateQuery) {
            // 3.11: a Gate_Query answer is not a repetition.
            val (recorded, notes) = recordGate(state, event.localTime, proactive = false, event.atMs, ctx)
            val spec = SpeechSpec.GateInfo(inResponseToQuery = true)
            return Transition(recorded, listOf(speak(spec, language)) + notes)
        }
        if (category == IntentCategory.UNKNOWN && phrases.matchesAny(phrases.acknowledgement, normalized)) {
            // 3.6: acknowledgement after the directions ends the call; before that it is ignored.
            if (!state.directionsDone) return Transition(state, emptyList())
            return endDelivery(state, ctx, EndReason.DELIVERY_DONE, hangUp = false)
        }
        return null
    }

    /**
     * A delivery Caller who states the address or asks for the location or directions gets the Location URL by
     * text message. A stated address is checked against the configured address first; a mismatch sends nothing,
     * because the delivery may be for another address. The address itself is never spoken. Null means: not a
     * location request, use the normal flow.
     */
    private fun locationRequest(
        state: ConversationState,
        text: String,
        event: ConversationEvent.CallerUtterance,
        language: Language,
        ctx: EngineContext,
    ): Transition? {
        if (phrases.matchesAny(phrases.repeatRequest, text)) return null
        val asksDirections = phrases.matchesAny(phrases.directionsRequest, text)
        val statesAddress = !asksDirections && phrases.matchesAny(phrases.addressStatement, text) &&
            AddressMatcher.hasAddressWords(text)
        if (!asksDirections && !statesAddress) return null
        val frozen = ctx.frozen
        val verify = statesAddress && !(frozen.address.isNullOrBlank() && frozen.addressHi.isNullOrBlank())
        if (verify && !AddressMatcher.matches(frozen.address, text, frozen.addressHi)) {
            return Transition(state, listOf(speak(SpeechSpec.AddressNotMatching, language)))
        }
        val number = ctx.callerNumber.orEmpty()
        if (frozen.mapLocation.isNullOrBlank() || !ctx.callerKnownNumber || number.isBlank()) {
            // Nothing can be texted: give the spoken directions and tell the User at the end of the call.
            return dispatch(state.copy(locationWanted = true), IntentCategory.DELIVERY, false, event, language, ctx)
        }
        val speak = speak(SpeechSpec.LocationSmsSent(addressConfirmed = verify), language)
        return Transition(
            state.copy(
                phase = Phase.DELIVERY,
                locationSent = true,
                directionsResponseId = speak.responseId,
                directionsDone = false,
            ),
            buildList {
                add(speak)
                if (!state.locationSent) add(Effect.SendMapSms(number))
            },
        )
    }
    /** Speaks the directions with the gate statement first; the clock is read from [event] (11.5 to 11.7). */
    private fun speakDirections(
        state: ConversationState,
        repetition: Int,
        proactive: Boolean,
        event: ConversationEvent.CallerUtterance,
        language: Language,
        ctx: EngineContext,
    ): Transition {
        val (recorded, notes) = recordGate(state, event.localTime, proactive, event.atMs, ctx)
        val speak = speak(SpeechSpec.DeliveryDirections(repetition = repetition, proactiveGate = true), language)
        return Transition(
            recorded.copy(phase = Phase.DELIVERY, directionsResponseId = speak.responseId, directionsDone = false),
            listOf<Effect>(speak) + notes,
        )
    }

    /** Appends the real gate statement starting now (10.9). An unreadable clock states both gates and is noted (11.17). */
    private fun recordGate(
        state: ConversationState,
        time: LocalTimeHm?,
        proactive: Boolean,
        atMs: Long,
        ctx: EngineContext,
    ): Pair<ConversationState, List<Effect>> {
        val number = when (val sel = selectGate(time, ctx.frozen.gates)) {
            is GateSelection.Single -> sel.gate.number
            is GateSelection.Both -> "${sel.gate1.number},${sel.gate3.number}"
        }
        val notes = if (time == null) listOf<Effect>(Effect.LogNote(LogNoteKind.TIME_READ_FAILED)) else emptyList()
        val statement = GateStatement(number, proactive = proactive, fictitious = false, atMs = atMs)
        return state.copy(gateStatements = state.gateStatements + statement) to notes
    }

    // ---- Stalling, silence, audio and failure handling (5.2 to 5.10, 6.x, 7.9, 8.7 to 8.9, 11.11) ----

    /** One stalling reply; the stalling start is recorded once (5.9). A Gate_Query gets a fictitious gate statement (11.11). */
    private fun stallReply(
        state: ConversationState,
        gateQuery: Boolean,
        event: ConversationEvent.CallerUtterance,
        language: Language,
    ): Transition {
        val statements = if (gateQuery) {
            state.gateStatements + GateStatement(FICTITIOUS_GATE, proactive = false, fictitious = true, atMs = event.atMs)
        } else state.gateStatements
        return Transition(
            state.copy(phase = Phase.STALLING, stallStartMs = state.stallStartMs ?: event.atMs, gateStatements = statements),
            listOf(speak(SpeechSpec.Stall(event.text.trim()), language)),
        )
    }

    /**
     * Tick rules. Reactions apply only while the Agent is active (no reaction in Take_Over, no speech when muted).
     * The driver restarts `silentForMs` after each Agent prompt, so one tick per timeout period triggers one prompt.
     */
    private fun onTick(state: ConversationState, event: ConversationEvent.Tick, ctx: EngineContext): Transition {
        if (state.phase == Phase.AWAITING_TAKEOVER && state.mode != Mode.TAKE_OVER) return awaitingTick(state, event)
        if (state.mode != Mode.AGENT_ACTIVE) return Transition(state, emptyList())
        if (state.phase == Phase.DELIVERY && state.directionsDone && event.silentForMs >= DELIVERY_SILENCE_MS) {
            return endDelivery(state, ctx, EndReason.DELIVERY_DONE, hangUp = false)
        }
        if (state.phase !in DISPATCH_PHASES) return Transition(state, emptyList())
        val language = ResponseLanguageResolver.resolve(null, state.lastLanguage)

        // 8.8: more than 10 s without usable audio ends the call and records the interruption.
        if (event.noAudioForMs > NO_AUDIO_LIMIT_MS) {
            return Transition(
                state.copy(
                    phase = Phase.ENDED,
                    interruption = InterruptionRecord(event.atMs - event.noAudioForMs, event.noAudioForMs),
                ),
                listOf(Effect.EndCall(EndReason.AUDIO_INTERRUPTED)),
            )
        }
        // 5.8: maximum stalling duration, ended politely.
        val stallStart = state.stallStartMs
        if (state.phase == Phase.STALLING && stallStart != null &&
            event.atMs - stallStart >= ctx.frozen.maxStallingSeconds * 1000L
        ) {
            return Transition(
                state.copy(phase = Phase.ENDED),
                listOf(speak(SpeechSpec.PoliteGoodbye, language), Effect.EndCall(EndReason.MAX_DURATION)),
            )
        }
        if (event.silentForMs < ctx.frozen.silenceTimeoutSeconds * 1000L) return Transition(state, emptyList())
        return when (state.phase) {
            // 6.4, 6.5: repeat the greeting once, then end.
            Phase.GREETING -> if (!state.greetingRepeated) {
                Transition(state.copy(greetingRepeated = true), listOf(speak(SpeechSpec.Greeting, language)))
            } else {
                Transition(
                    state.copy(phase = Phase.ENDED),
                    listOf(
                        Effect.LogNote(LogNoteKind.CALLER_SILENT),
                        Effect.Notify(NotificationKind.SILENT_CALLER),
                        Effect.EndCall(EndReason.CALLER_SILENCE),
                    ),
                )
            }
            // 5.6: 3 consecutive unanswered prompts, then the call ends.
            Phase.STALLING -> if (state.silencePrompts < MAX_SILENCE_PROMPTS) {
                Transition(
                    state.copy(silencePrompts = state.silencePrompts + 1),
                    listOf(speak(SpeechSpec.SilencePrompt, language)),
                )
            } else {
                Transition(
                    state.copy(phase = Phase.ENDED),
                    listOf(
                        speak(SpeechSpec.PoliteGoodbye, language),
                        Effect.LogNote(LogNoteKind.CALLER_SILENT),
                        Effect.EndCall(EndReason.CALLER_SILENCE),
                    ),
                )
            }
            else -> Transition(state, emptyList())
        }
    }

    /** 8.7: ask to repeat in the last detected language, at most 3 consecutive times; the tick rule then ends the call. */
    private fun onAudioUnusable(state: ConversationState): Transition {
        if (!canDispatch(state) || state.garbledAsks >= MAX_GARBLED_ASKS) return Transition(state, emptyList())
        val language = ResponseLanguageResolver.resolve(null, state.lastLanguage)
        return Transition(
            state.copy(garbledAsks = state.garbledAsks + 1),
            listOf(speak(SpeechSpec.AskRepeatRequest, language)),
        )
    }

    /**
     * 8.9, 5.10. `attempt` counts failures of the same response (1 = first). In stalling the first failure speaks
     * the filler (the responder retries), the second ends the call politely and logs it.
     */
    private fun onResponseFailed(state: ConversationState, event: ConversationEvent.ResponseFailed): Transition {
        if (state.mode != Mode.AGENT_ACTIVE) return Transition(state, emptyList())
        val language = ResponseLanguageResolver.resolve(null, state.lastLanguage)
        if (state.phase == Phase.STALLING) {
            return if (event.attempt <= 1) {
                Transition(state, listOf(speak(SpeechSpec.StallFiller, language)))
            } else {
                Transition(
                    state.copy(phase = Phase.ENDED),
                    listOf(
                        speak(SpeechSpec.PoliteGoodbye, language),
                        Effect.LogNote(LogNoteKind.STALLING_REPLY_FAILED),
                        Effect.EndCall(EndReason.RESPONSE_FAILURE),
                    ),
                )
            }
        }
        if (state.phase !in DISPATCH_PHASES) return Transition(state, emptyList())
        return Transition(
            state,
            listOf(speak(SpeechSpec.FallbackPhrase, language), Effect.LogNote(LogNoteKind.PROCESSING_ERROR)),
        )
    }

    /**
     * 7.9. The first failure is retried by the speech adapter (no effect). The second gives the fallback phrase in
     * the last working language and keeps the call open.
     */
    private fun onSynthesisFailed(state: ConversationState, event: ConversationEvent.SpeechSynthesisFailed): Transition {
        if (state.mode != Mode.AGENT_ACTIVE || event.attempt <= 1) return Transition(state, emptyList())
        val language = ResponseLanguageResolver.resolve(null, state.lastLanguage)
        return Transition(
            state,
            listOf(speak(SpeechSpec.FallbackPhrase, language), Effect.LogNote(LogNoteKind.SPEECH_SYNTHESIS_FAILED)),
        )
    }

    // ---- Transfer (4.1 to 4.11, 8.10) ----

    private fun onSpeechFinished(state: ConversationState, event: ConversationEvent.AgentSpeechFinished, ctx: EngineContext): Transition {
        val base = state.copy(
            spokenResponseIds = state.spokenResponseIds + event.responseId,
            directionsDone = state.directionsDone || event.responseId == state.directionsResponseId,
        )
        val isNotice = event.responseId == state.transferNoticeResponseId
        if (!isNotice) return Transition(base, emptyList())
        val cleared = base.copy(transferNoticeResponseId = null)
        if (state.transfer != null || state.phase != Phase.LISTENING || state.mode == Mode.TAKE_OVER) {
            return Transition(cleared, emptyList())
        }
        val target = ctx.frozen.transferTarget
        val hasTarget = !target.isNullOrBlank()
        val callerNumber = if (ctx.callerKnownNumber) ctx.callerNumber else null
        if (ctx.frozen.pathAEnabled && hasTarget) {
            return Transition(
                cleared.copy(
                    phase = Phase.TRANSFER,
                    transfer = TransferState(TransferPath.PATH_A_MERGE, event.atMs),
                ),
                listOf(Effect.StartTransfer(target.orEmpty(), callerNumber), Effect.Notify(NotificationKind.TRANSFER_STARTED)),
            )
        }
        val reason = if (hasTarget) AlertReason.PATH_A_UNAVAILABLE else AlertReason.TARGET_NOT_CONFIGURED
        val alert = alertTransition(
            cleared, TransferState(TransferPath.PATH_B_ALERT, event.atMs), reason, event.atMs, callerNumber,
        )
        val effects = alert.effects + Effect.Notify(NotificationKind.TRANSFER_STARTED) +
            (if (hasTarget) emptyList() else listOf(Effect.Notify(NotificationKind.TRANSFER_TARGET_MISSING)))
        return Transition(alert.state, effects)
    }

    /** Raises the Takeover_Alert, says "please wait" at once (unless muted) and enters AWAITING_TAKEOVER (4.2, 4.7). */
    private fun alertTransition(
        state: ConversationState,
        transfer: TransferState,
        reason: AlertReason,
        atMs: Long,
        callerNumber: String?,
    ): Transition {
        val effects = mutableListOf<Effect>(Effect.RaiseTakeoverAlert(callerNumber, reason))
        var lastWait: Long? = null
        if (state.mode == Mode.AGENT_ACTIVE) {
            effects += speak(SpeechSpec.PleaseWait, ResponseLanguageResolver.resolve(null, state.lastLanguage))
            lastWait = atMs
        }
        return Transition(
            state.copy(
                phase = Phase.AWAITING_TAKEOVER,
                transfer = transfer.copy(alertReason = reason, alertRaisedAtMs = atMs, lastPleaseWaitAtMs = lastWait),
            ),
            effects,
        )
    }

    private fun onTransferResult(state: ConversationState, event: ConversationEvent.TransferResult, ctx: EngineContext): Transition {
        val transfer = state.transfer
        if (transfer != null && state.mode == Mode.TAKE_OVER && state.phase == Phase.TRANSFER) {
            // After a take over a result only updates the record (4.8): a merge is recorded, nothing else reacts.
            val updated = if (event.result == PathAResult.MERGED) transfer.copy(outcome = TransferOutcome.MERGED) else transfer
            return Transition(state.copy(transfer = updated), emptyList())
        }
        if (state.phase != Phase.TRANSFER || transfer == null || state.mode == Mode.TAKE_OVER) return Transition(state, emptyList())
        if (event.result == PathAResult.MERGED) {
            return Transition(
                state.copy(phase = Phase.TRANSFERRED, transfer = transfer.copy(outcome = TransferOutcome.MERGED)),
                emptyList(),
            )
        }
        val failed = event.result == PathAResult.FAILED
        val reason = if (failed) AlertReason.FAILED_THEN_ALERT else AlertReason.NO_ANSWER_30S
        val callerNumber = if (ctx.callerKnownNumber) ctx.callerNumber else null
        val alert = alertTransition(state, transfer, reason, event.atMs, callerNumber)
        val extra = if (failed) listOf(Effect.Notify(NotificationKind.TRANSFER_FAILED), Effect.LogNote(LogNoteKind.TRANSFER_FAILED)) else emptyList()
        return Transition(alert.state, alert.effects + extra)
    }

    /**
     * AWAITING_TAKEOVER ticks. The 30 s rule comes first and runs even while muted (13.20); speech needs an active
     * Agent, and a muted Agent leaves `nobodyAvailablePending` for the resume handler.
     */
    private fun awaitingTick(state: ConversationState, event: ConversationEvent.Tick): Transition {
        val transfer = state.transfer ?: return Transition(state, emptyList())
        if (transfer.outcome == TransferOutcome.TAKEN_OVER_BY_USER) return Transition(state, emptyList())
        val language = ResponseLanguageResolver.resolve(null, state.lastLanguage)
        val active = state.mode == Mode.AGENT_ACTIVE
        val raisedAt = transfer.alertRaisedAtMs
        if (raisedAt != null && event.atMs - raisedAt >= TAKEOVER_WAIT_MS) {
            return Transition(
                state.copy(
                    phase = Phase.LISTENING,
                    transfer = transfer.copy(outcome = TransferOutcome.NO_TAKEOVER_30S, nobodyAvailablePending = !active),
                ),
                if (active) listOf(speak(SpeechSpec.NobodyAvailable, language)) else emptyList(),
            )
        }
        val last = transfer.lastPleaseWaitAtMs
        if (active && (last == null || event.atMs - last >= PLEASE_WAIT_INTERVAL_MS)) {
            return Transition(
                state.copy(transfer = transfer.copy(lastPleaseWaitAtMs = event.atMs)),
                listOf(speak(SpeechSpec.PleaseWait, language)),
            )
        }
        return Transition(state, emptyList())
    }

    private fun onCallEnded(state: ConversationState, event: ConversationEvent.CallEnded, ctx: EngineContext): Transition {
        val transfer = state.transfer
        if (state.phase == Phase.AWAITING_TAKEOVER && transfer != null) {
            return Transition(
                state.copy(
                    phase = Phase.ENDED,
                    transfer = if (transfer.outcome == TransferOutcome.TAKEN_OVER_BY_USER) transfer
                    else transfer.copy(outcome = TransferOutcome.CALL_ENDED_WHILE_WAITING),
                ),
                emptyList(),
            )
        }
        if (state.phase == Phase.DELIVERY && state.directionsDone && event.by == EndedBy.CALLER) {
            return endDelivery(state, ctx, EndReason.CALLER_HUNG_UP, hangUp = true)
        }
        return Transition(state, emptyList())
    }

    /** Ends the delivery: notify the User if a location was wanted but could not be texted, then EndCall unless the Caller hung up. */
    private fun endDelivery(state: ConversationState, ctx: EngineContext, reason: EndReason, hangUp: Boolean): Transition {
        val effects = mutableListOf<Effect>()
        if (state.locationWanted && ctx.frozen.mapLocation.isNullOrBlank()) {
            effects += Effect.Notify(NotificationKind.MAP_LOCATION_MISSING)
        }
        if (!hangUp) effects += Effect.EndCall(reason)
        return Transition(state.copy(phase = Phase.ENDED), effects)
    }

    /** Responses are produced only while the Agent is active and the call is in a conversational phase. */
    private fun canDispatch(state: ConversationState): Boolean =
        state.mode == Mode.AGENT_ACTIVE && state.phase in DISPATCH_PHASES

    /** The dispatch table of the design. [category] is the category of this utterance. */
    private fun dispatch(
        state: ConversationState,
        category: IntentCategory,
        gateQuery: Boolean,
        event: ConversationEvent.CallerUtterance,
        language: Language,
        ctx: EngineContext,
    ): Transition = if (state.phase == Phase.STALLING &&
        (category == IntentCategory.UNKNOWN || category == IntentCategory.SOLICITATION)
    ) {
        // 5.2: every Caller utterance in Stalling_Mode gets one stalling reply in the language of that utterance.
        stallReply(state, gateQuery, event, language)
    } else when (category) {
        // The directions always start with gate information (3.10, 11.8). A gate query is not a repetition (3.11).
        IntentCategory.DELIVERY -> speakDirections(state, state.repeatsUsed, proactive = !gateQuery, event, language, ctx)
        IntentCategory.DOOR_NOT_OPENED -> {
            val (recorded, notes) =
                if (gateQuery) recordGate(state, event.localTime, proactive = false, event.atMs, ctx) else state to emptyList()
            val notice = speak(SpeechSpec.TransferNotice, language)
            Transition(
                recorded.copy(phase = Phase.LISTENING, transferNoticeResponseId = notice.responseId),
                buildList {
                    if (gateQuery) add(speak(SpeechSpec.GateInfo(inResponseToQuery = true), language))
                    add(notice)
                    addAll(notes)
                },
            )
        }
        IntentCategory.SOLICITATION -> stallReply(state, gateQuery, event, language)
        // 6.2: still UNKNOWN after 2 consecutive clarification prompts. A Gate_Query utterance is not a prompt (11.10).
        IntentCategory.UNKNOWN -> if (!gateQuery && state.clarifyPrompts >= MAX_CLARIFY_PROMPTS) {
            Transition(
                state.copy(phase = Phase.ENDED),
                listOf(
                    speak(SpeechSpec.WillReturnCall, language),
                    Effect.Notify(NotificationKind.UNKNOWN_CALLER_INTENT),
                    Effect.EndCall(EndReason.UNKNOWN_AFTER_CLARIFY),
                ),
            )
        } else {
            val (recorded, notes) =
                if (gateQuery) recordGate(state, event.localTime, proactive = false, event.atMs, ctx) else state to emptyList()
            Transition(
                if (gateQuery) recorded.copy(phase = Phase.CLARIFYING)
                else recorded.copy(phase = Phase.CLARIFYING, clarifyPrompts = recorded.clarifyPrompts + 1),
                buildList {
                    if (gateQuery) add(speak(SpeechSpec.GateInfo(inResponseToQuery = true), language))
                    add(speak(SpeechSpec.AskReason, language))
                    addAll(notes)
                },
            )
        }
    }

    private fun speak(spec: SpeechSpec, language: Language): Effect.Speak = Effect.Speak(ids.next(), spec, language)

    private companion object {
        const val TAKEOVER_WAIT_MS = 30_000L
        const val PLEASE_WAIT_INTERVAL_MS = 10_000L
        const val MAX_REPETITIONS = 3
        const val DELIVERY_SILENCE_MS = 10_000L
        const val NO_AUDIO_LIMIT_MS = 10_000L
        const val MAX_CLARIFY_PROMPTS = 2
        const val MAX_SILENCE_PROMPTS = 3
        const val MAX_GARBLED_ASKS = 3
        const val FICTITIOUS_GATE = "FICTITIOUS"
        val DISPATCH_PHASES = setOf(Phase.ANSWERING, Phase.GREETING, Phase.LISTENING, Phase.DELIVERY, Phase.STALLING, Phase.CLARIFYING)
    }
}
