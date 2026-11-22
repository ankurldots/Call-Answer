package com.callanswer.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.audio.AgentAudioPlayer
import com.callanswer.app.audio.AgentAudioPlayerImpl
import com.callanswer.app.nlg.DefaultStallingResponder
import com.callanswer.app.nlg.StallContext
import com.callanswer.app.nlg.StallingResponder
import com.callanswer.app.sms.MapSmsSender
import com.callanswer.app.telecom.TelecomCallAnswerer
import com.callanswer.app.translate.MlKitStepTranslator
import com.callanswer.app.tts.AndroidTtsSynthesizer
import com.callanswer.app.tts.DirectoryPiperVoiceLocator
import com.callanswer.app.tts.FallbackPhrase
import com.callanswer.app.tts.FallbackPhrases
import com.callanswer.app.tts.FallbackSynthesizer
import com.callanswer.app.tts.SherpaPiperSynthesizer
import com.callanswer.app.tts.SpeechSynthesizerPort
import com.callanswer.app.tts.synthesizeSentences
import com.callanswer.app.cloudtts.PollyCacheSynthesizer
import com.callanswer.core.engine.CallSession
import com.callanswer.core.engine.ConversationEvent
import com.callanswer.core.engine.Effect
import com.callanswer.core.engine.ResponseRenderer
import com.callanswer.core.engine.SequentialResponseIdGenerator
import com.callanswer.core.engine.SpeechSpec
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.model.LogNoteKind
import com.callanswer.core.model.NotificationKind
import com.callanswer.core.model.Utterance
import com.callanswer.core.model.VoiceSubstitution
import com.callanswer.core.network.ProviderRouter
import com.callanswer.core.network.cloudDeadlineMs
import com.callanswer.core.stalling.FictitiousReplyLibrary
import com.callanswer.core.stalling.PiiGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * Carries out the engine effects of one call (Requirements 3.1, 3.4, 3.8, 4.2, 4.7, 7.9, 8.9, 9.19, 13.6, 13.10,
 * 13.12, 13.14). [handle] never blocks: speech and the end of the call go through one ordered queue, so the
 * effects of a transition keep their order (a goodbye is played before the call is ended). Every failure is turned
 * into an engine event or a log note here; none reaches the caller.
 *
 * Speak: the text comes from [renderer] (or [stalling] for `Stall`), is synthesized sentence by sentence and
 * played through [player]. Synthesis is retried once; then the bundled WAV for the phrase is played when there is
 * one, otherwise `SpeechSynthesisFailed(2)` makes the engine speak its fallback phrase. `AgentSpeechFinished` is
 * emitted when the speech ended, also after a failure, so that the conversation never waits for audio that will
 * not come. A `StopSpeaking` cancels the speech in progress and everything queued before it, and emits nothing.
 */
class EffectExecutor(
    private val session: CallSession,
    private val scope: CoroutineScope,
    private val emit: ((Long) -> ConversationEvent) -> Unit,
    private val history: () -> List<Utterance>,
    private val synthesizer: SpeechSynthesizerPort,
    private val player: AgentAudioPlayer,
    private val renderer: ResponseRenderer,
    private val stalling: StallingResponder,
    private val fallbacks: FallbackPhrases?,
    private val alerts: TakeoverAlertPresenter,
    private val notifications: NotificationCenter,
    private val smsSender: MapSmsSender,
    private val endCall: () -> Boolean,
    private val onAgentUtterance: (text: String, startMs: Long, endMs: Long) -> Unit,
    private val logNote: (LogNoteKind) -> Unit,
    private val onVoiceSubstitution: (VoiceSubstitution) -> Unit = {},
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) : EffectSink {

    private sealed interface Item {
        data class Say(val effect: Effect.Speak, val gen: Int, val attempt: Int) : Item
        data class End(val reason: EndReason) : Item
        class Barrier(val done: CompletableDeferred<Unit>) : Item
    }

    private val queue = Channel<Item>(Channel.UNLIMITED)
    private val generation = AtomicInteger(0)
    private val frozen = session.frozen
    private val worker: Job = scope.launch(Dispatchers.Default) {
        for (item in queue) {
            try {
                when (item) {
                    is Item.Say -> say(item)
                    is Item.End -> end(item.reason)
                    is Item.Barrier -> item.done.complete(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "effect failed", t)
                logNote(LogNoteKind.PROCESSING_ERROR)
            }
        }
    }

    override fun handle(session: CallSession, effect: Effect) {
        try {
            when (effect) {
                is Effect.Speak -> queue.trySend(Item.Say(effect, generation.get(), 1))
                Effect.StopSpeaking -> {
                    generation.incrementAndGet()
                    player.stop()
                }
                is Effect.RaiseTakeoverAlert -> alerts.raise(effect.callerNumber, effect.reason)
                Effect.ClearTakeoverAlert -> alerts.clear()
                is Effect.SendMapSms -> sendMapSms(effect.number)
                is Effect.Notify -> notify(effect.kind)
                is Effect.EndCall -> queue.trySend(Item.End(effect.reason))
                is Effect.LogNote -> logNote(effect.note)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "effect $effect failed", t)
            logNote(LogNoteKind.PROCESSING_ERROR)
        }
    }

    /** Waits until everything queued so far has been played and carried out, at most [timeoutMs]. */
    suspend fun drain(timeoutMs: Long): Boolean {
        val done = CompletableDeferred<Unit>()
        if (queue.trySend(Item.Barrier(done)).isFailure) return true
        return withTimeoutOrNull(timeoutMs) { done.await() } != null
    }

    /** Stops speech and the alert and releases the synthesizer. In-flight map SMS results are not affected. */
    fun close() {
        generation.incrementAndGet()
        queue.close()
        worker.cancel()
        runCatching { player.stop() }
        runCatching { alerts.clear() }
        runCatching { synthesizer.release() }
    }

    // ---- Speak ----

    private suspend fun say(item: Item.Say) {
        if (item.gen != generation.get()) return
        val spec = item.effect.spec
        val language = item.effect.language
        val text = try {
            renderText(spec, language)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "response failed", t)
            responseFailed(item)
            return
        }
        if (item.gen != generation.get()) return
        val gender = frozen.voiceGender
        var failure: Throwable? = null
        var spoke = false
        var startMs = -1L
        for (attempt in 1..2) {
            failure = null
            try {
                synthesizer.synthesizeSentences(text, language, gender)
                    .takeWhile { item.gen == generation.get() }
                    .collect { r ->
                        if (startMs < 0) startMs = clockMs()
                        if (r.substituted) {
                            Log.i(TAG, "voice of another gender used")
                            onVoiceSubstitution(VoiceSubstitution(r.voice.language, r.voice.id))
                        }
                        player.play(r.pcm, r.sampleRate, item.effect.responseId)
                        spoke = true
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failure = t
                if (spoke) break
            }
            if (failure == null) break
        }
        if (failure != null && !spoke) {
            Log.w(TAG, "speech synthesis failed", failure)
            val clip = playFallbackClip(item, spec, language, text)
            if (!clip) {
                if (spec is SpeechSpec.FallbackPhrase) logNote(LogNoteKind.SPEECH_SYNTHESIS_FAILED)
                else emit { ConversationEvent.SpeechSynthesisFailed(it, 2) }
            }
        } else if (spoke) {
            onAgentUtterance(text, startMs, clockMs())
        }
        if (item.gen == generation.get()) {
            emit { ConversationEvent.AgentSpeechFinished(it, item.effect.responseId) }
        }
    }

    /** Plays the bundled WAV for [spec] when there is one (7.9, 8.9). Returns true when it was played. */
    private suspend fun playFallbackClip(item: Item.Say, spec: SpeechSpec, language: Language, text: String): Boolean {
        val phrase = when (spec) {
            SpeechSpec.FallbackPhrase -> FallbackPhrase.FALLBACK
            SpeechSpec.StallFiller -> FallbackPhrase.FILLER
            SpeechSpec.PleaseWait -> FallbackPhrase.PLEASE_WAIT
            SpeechSpec.NobodyAvailable -> FallbackPhrase.NOBODY_AVAILABLE
            else -> return false
        }
        val clip = runCatching { fallbacks?.load(phrase, language, frozen.voiceGender) }.getOrNull() ?: return false
        if (item.gen != generation.get()) return true
        if (clip.substituted) onVoiceSubstitution(VoiceSubstitution(language, "fallback-${clip.gender.name.lowercase()}"))
        val start = clockMs()
        player.play(clip.pcm, clip.sampleRate, item.effect.responseId)
        onAgentUtterance(text, start, clockMs())
        if (spec !is SpeechSpec.FallbackPhrase) logNote(LogNoteKind.SPEECH_SYNTHESIS_FAILED)
        return true
    }

    private suspend fun renderText(spec: SpeechSpec, language: Language): String {
        val finalSpec = if (spec is SpeechSpec.Stall) {
            val reply = stalling.reply(
                StallContext(
                    callerText = spec.callerText,
                    language = language,
                    gateQuery = false,
                    history = history(),
                    deadlineMs = cloudDeadlineMs(0),
                ),
            )
            SpeechSpec.Stall(reply.text)
        } else {
            spec
        }
        return renderer.text(finalSpec, language, frozen, localTime(), System.nanoTime())
    }

    /** A stalling reply that failed is retried once after the engine's filler phrase; a second failure ends the call (5.10, 8.9). */
    private fun responseFailed(item: Item.Say) {
        val spec = item.effect.spec
        if (spec is SpeechSpec.Stall) {
            emit { ConversationEvent.ResponseFailed(it, item.attempt) }
            if (item.attempt < 2) queue.trySend(item.copy(attempt = item.attempt + 1))
        } else if (spec !is SpeechSpec.FallbackPhrase) {
            emit { ConversationEvent.ResponseFailed(it, 1) }
        }
    }

    private fun localTime(): LocalTimeHm? = runCatching {
        val t = LocalTime.now(session.zone)
        LocalTimeHm(t.hour, t.minute)
    }.getOrNull()

    // ---- Other effects ----

    private fun sendMapSms(number: String) {
        val location = frozen.mapLocation?.trim().orEmpty()
        if (number.isBlank() || location.isEmpty()) return
        // Not tied to the call scope: the failure notification must still be posted when the call ends first.
        backgroundScope.launch {
            val result = runCatching { smsSender.send(number, location) }.getOrNull()
            if (result != com.callanswer.app.sms.MapSmsResult.DELIVERED) logNote(LogNoteKind.MAP_SMS_FAILED)
        }
    }

    private fun notify(kind: NotificationKind) {
        val number = session.callerNumber.orEmpty()
        when (kind) {
            NotificationKind.MAP_LOCATION_MISSING -> notifications.mapLocationMissing(number)
            NotificationKind.MAP_SMS_FAILED -> notifications.mapSmsFailed(number)
            NotificationKind.TRANSFER_STARTED -> notifications.transferStarted(number)
            NotificationKind.TRANSFER_FAILED -> notifications.transferFailed(number)
            NotificationKind.TRANSFER_TARGET_MISSING -> notifications.transferTargetNotConfigured(number)
            // Post-call notifications are posted by the call-end finalization.
            NotificationKind.SILENT_CALLER,
            NotificationKind.UNKNOWN_CALLER_INTENT,
            NotificationKind.CALL_NOT_LOGGED,
            NotificationKind.SPAM_CALL_SCREENED,
            -> Unit
        }
    }

    private fun end(reason: EndReason) {
        if (!endCall()) logNote(LogNoteKind.PROCESSING_ERROR)
        Log.i(TAG, "call ended by the App: $reason")
    }

    companion object {
        private const val TAG = "EffectExecutor"
        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Builds the executor with the real audio, TTS, ASR-independent and telephony parts. */
        fun create(
            context: Context,
            scope: CoroutineScope,
            session: CallSession,
            pipeline: CallPipeline,
            emit: ((Long) -> ConversationEvent) -> Unit,
            history: () -> List<Utterance>,
            logNote: (LogNoteKind) -> Unit,
            onVoiceSubstitution: (VoiceSubstitution) -> Unit = {},
        ): EffectExecutor {
            val app = context.applicationContext
            val frozen = session.frozen
            val protected = (frozen.protectedTerms + listOfNotNull(frozen.mapLocation, frozen.address, frozen.addressHi, frozen.transferTarget)).toSet()
            val notifications = NotificationCenter(app)
            val player = AgentAudioPlayerImpl(
                pipeline.echoGate,
                audioManager = app.getSystemService(android.media.AudioManager::class.java),
                usagesProvider = { com.callanswer.app.audio.AgentVoiceUsage.orderFor(app) },
            )
            // Alert tones use their own track so they never cut the Agent's speech; both mark the same EchoGate.
            val tonePlayer = AgentAudioPlayerImpl(
                pipeline.echoGate,
                audioManager = app.getSystemService(android.media.AudioManager::class.java),
                usagesProvider = { com.callanswer.app.audio.AgentVoiceUsage.orderFor(app) },
            )
            return EffectExecutor(
                session = session,
                scope = scope,
                emit = emit,
                history = history,
                // Cached Polly audio first (offline), then Piper, then the platform TTS.
                synthesizer = FallbackSynthesizer(
                    PollyCacheSynthesizer((app as CallAnswerApp).pollyCache),
                    FallbackSynthesizer(
                        SherpaPiperSynthesizer(DirectoryPiperVoiceLocator(File(app.filesDir, "tts-voices"))),
                        AndroidTtsSynthesizer(app),
                    ),
                ),
                player = player,
                renderer = ResponseRenderer(SequentialResponseIdGenerator(session.id), MlKitStepTranslator()),
                // No cloud endpoint is configurable yet, so stalling replies are local (cloud = null).
                stalling = DefaultStallingResponder(
                    FictitiousReplyLibrary(protected),
                    PiiGuard(protected),
                    ProviderRouter { pipeline.network.quality.value },
                    cloud = null,
                ),
                fallbacks = FallbackPhrases(app.assets),
                alerts = TakeoverAlertPresenterImpl(app, tonePlayer, scope),
                notifications = notifications,
                smsSender = MapSmsSender(app, notifyFailure = { notifications.mapSmsFailed(it) }),
                endCall = { TelecomCallAnswerer(app, session.callerNumber).endCall() },
                onAgentUtterance = { text, s, e -> pipeline.noteAgentUtterance(text, s, e) },
                logNote = logNote,
                onVoiceSubstitution = onVoiceSubstitution,
            )
        }
    }
}
