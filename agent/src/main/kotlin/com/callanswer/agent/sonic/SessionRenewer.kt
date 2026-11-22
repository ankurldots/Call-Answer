package com.callanswer.agent.sonic

import com.callanswer.core.model.Utterance
import com.callanswer.core.stalling.PiiGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Timing of the renewal procedure (design: "Duration limit and renewal"). */
data class RenewalConfig(
    /** `sonic.maxSessionSeconds`; Sonic cuts a stream hard at 480 s with `ModelTimeoutException`. */
    val maxSessionSeconds: Int = 480,
    /** `sonic.idleSeconds`: a session without Caller or Agent activity for this long is renewed. */
    val idleSeconds: Int = 55,
    /** The replacement is requested at this share of [maxSessionSeconds]. */
    val renewAtFraction: Double = 0.8,
    /** Switch at the latest this long after the replacement is ready. */
    val maxSwitchWaitMs: Long = 3_000,
    /** Length of the Caller ring buffer. */
    val ringBufferMs: Long = 1_000,
    /** How long to wait for a late FINAL text of the Agent before the history is built. */
    val finalTextWaitMs: Long = 1_000,
    val pollMs: Long = 50,
    val retryDelayMs: Long = 250,
    val maxAttempts: Int = 3,
) {
    /** Time after the session start at which the replacement is requested (not after the hard limit). */
    val renewAfterMs: Long get() = (maxSessionSeconds * 1000L * renewAtFraction).toLong().coerceAtMost(maxSessionSeconds * 1000L - 1)
}

/** Why a replacement was requested. */
enum class RenewReason { LIMIT, IDLE, STREAM_END }

/** What the renewer tells its owner besides the Sonic events. */
sealed interface RenewNotice {
    /** The replacement is now the active session. */
    data class Renewed(val reason: RenewReason, val generation: Int, val replayedBytes: Int) : RenewNotice
    /** No replacement could be started; the call has no Sonic session any more. */
    data class Failed(val reason: RenewReason, val message: String) : RenewNotice
}

/** Caller audio kept for the last [ringBufferMs] (16 kHz PCM16 mono). */
internal class AudioRing(ms: Long) {
    private val capacity = (16_000 * 2 * ms / 1000).toInt()
    private val buf = java.io.ByteArrayOutputStream()
    @Synchronized fun add(pcm: ByteArray) {
        buf.write(pcm)
        if (buf.size() > capacity) {
            val all = buf.toByteArray()
            buf.reset()
            buf.write(all, all.size - capacity, capacity)
        }
    }
    @Synchronized fun drain(): ByteArray { val r = buf.toByteArray(); buf.reset(); return r }
}

/**
 * Keeps one Sonic conversation alive across the 8-minute stream limit, the idle limit, and abrupt stream ends.
 *
 * Procedure: open a replacement with the same prompt, tools, and voice and with history built from the
 * stored transcript; switch at a quiet moment or after [RenewalConfig.maxSwitchWaitMs]; replay the Caller
 * ring buffer into the replacement once; end the old session. The Caller audio goes to exactly one session at
 * any time. All session events are merged in [events]; lifecycle events (`StreamEnded`, `StreamError`,
 * `StartFailed`) of the sessions are handled here and are not forwarded.
 *
 * C-5: the hard cut comes without a warning, the replacement is ready in about 0.25 s, and the FINAL text of
 * the Agent arrives after its audio, so the history is built after that text has been recorded.
 */
class SessionRenewer(
    private val scope: CoroutineScope,
    private val config: RenewalConfig,
    private val voiceId: String,
    private val systemPrompt: String,
    /** Creates a new, not yet started session (fresh [SessionIds]). */
    private val newSession: () -> SonicSession,
    private val transcript: () -> List<Utterance>,
    private val serverState: () -> ServerState,
    private val guard: () -> PiiGuard? = { null },
    private val tools: List<ToolSpec> = ToolSpecs.all,
    /** True when no Caller speaks and no Agent audio is queued. */
    private val isQuiet: () -> Boolean = { true },
    private val clockMs: () -> Long = System::currentTimeMillis,
    /** Called for every forwarded event before waiters are released; record transcripts here. */
    private val onEvent: (SonicEvent) -> Unit = {},
    private val onNotice: (RenewNotice) -> Unit = {},
) {
    private val out = Channel<SonicEvent>(Channel.UNLIMITED)
    val events: Flow<SonicEvent> = out.receiveAsFlow()

    private val lock = Any()
    private val ring = AudioRing(config.ringBufferMs)
    private var active: SonicSession? = null
    private var activeStartedMs = 0L
    @Volatile private var lastActivityMs = 0L
    @Volatile private var awaitingFinal = false
    private val renewing = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var pending: SonicSession? = null
    private var pendingFatal = false
    private var monitor: Job? = null
    var generation = 0
        private set

    /** Opens the first session. Returns false when it could not be started. */
    fun start(history: List<HistoryMessage> = emptyList()): Boolean {
        val s = newSession()
        collect(s)
        if (!s.start(voiceId, systemPrompt, history, tools)) return false
        synchronized(lock) {
            active = s
            generation = 1
            activeStartedMs = clockMs()
            lastActivityMs = activeStartedMs
        }
        monitor = scope.launch { monitorLoop() }
        return true
    }

    /** Caller audio, 16 kHz PCM16 mono. Goes to the active session and into the ring buffer. */
    fun sendAudio(pcm16k: ByteArray) {
        synchronized(lock) {
            ring.add(pcm16k)
            active?.sendAudio(pcm16k)
        }
    }

    /** The active session, for instructions and tool-related calls. Changes at each renewal. */
    fun current(): SonicSession? = synchronized(lock) { active }

    /** Ends the active session and stops the renewal. */
    fun end() {
        if (!stopped.compareAndSet(false, true)) return
        monitor?.cancel()
        val (a, p) = synchronized(lock) { active.also { active = null } to pending.also { pending = null } }
        a?.end()
        p?.end()
        out.close()
    }

    private suspend fun monitorLoop() {
        while (scope.isActive && !stopped.get()) {
            delay(config.pollMs)
            val (act, startedMs) = synchronized(lock) { active to activeStartedMs }
            if (act == null || renewing.get()) continue
            val now = clockMs()
            when {
                now - startedMs >= config.renewAfterMs -> renew(RenewReason.LIMIT)
                now - lastActivityMs >= config.idleSeconds * 1000L -> renew(RenewReason.IDLE)
            }
        }
    }

    private fun collect(s: SonicSession) {
        scope.launch {
            s.events.collect { e ->
                when (e) {
                    is SonicEvent.StreamEnded -> lifecycle(s, "stream ended", RenewReason.STREAM_END)
                    is SonicEvent.StreamError -> lifecycle(s, e.message, RenewReason.STREAM_END)
                    is SonicEvent.StartFailed -> lifecycle(s, e.message, RenewReason.STREAM_END)
                    else -> forward(e)
                }
            }
        }
    }

    private fun forward(e: SonicEvent) {
        when (e) {
            is SonicEvent.AgentAudio -> { awaitingFinal = true; lastActivityMs = clockMs() }
            is SonicEvent.UserSpeechStarted, is SonicEvent.CallerTranscript -> lastActivityMs = clockMs()
            else -> Unit
        }
        onEvent(e)
        if (e is SonicEvent.AgentTranscript || e is SonicEvent.Interrupted || e is SonicEvent.Completed) awaitingFinal = false
        out.trySend(e)
    }

    /** A session reported the end of its stream. Only the active or pending one matters. */
    private fun lifecycle(s: SonicSession, message: String, reason: RenewReason) {
        val recover = synchronized(lock) {
            when {
                s === pending -> { pendingFatal = true; false }
                s === active && !stopped.get() -> true
                else -> false
            }
        }
        if (recover) scope.launch { renew(reason, failedMessage = message) }
    }

    /**
     * The renewal procedure. [failedMessage] is set when the active stream is already gone (abrupt end or
     * hard cut); then the switch happens at once.
     */
    suspend fun renew(reason: RenewReason, failedMessage: String? = null) {
        if (stopped.get() || !renewing.compareAndSet(false, true)) return
        try {
            var lastError = failedMessage ?: "renewal failed"
            repeat(config.maxAttempts) { attempt ->
                if (stopped.get()) return
                if (attempt > 0) delay(config.retryDelayMs)
                waitForFinalText()
                val history = HistoryBuilder.build(transcript(), serverState(), guard())
                val s = newSession()
                synchronized(lock) { pending = s; pendingFatal = false }
                collect(s)
                if (!s.start(voiceId, systemPrompt, history, tools)) {
                    synchronized(lock) { if (pending === s) pending = null }
                    lastError = "replacement start failed"
                    return@repeat
                }
                if (failedMessage == null) waitForQuietMoment()
                if (switchTo(s, reason)) return
                lastError = "replacement stream ended before the switch"
            }
            if (!stopped.get()) onNotice(RenewNotice.Failed(reason, lastError))
        } finally {
            renewing.set(false)
        }
    }

    /** The late FINAL text of the last Agent turn must be in the transcript before the history is built. */
    private suspend fun waitForFinalText() {
        var waited = 0L
        while (awaitingFinal && waited < config.finalTextWaitMs) {
            delay(config.pollMs)
            waited += config.pollMs
        }
    }

    private suspend fun waitForQuietMoment() {
        var waited = 0L
        while (waited < config.maxSwitchWaitMs && !isQuiet() && !pendingFatalNow() && !stopped.get()) {
            delay(config.pollMs)
            waited += config.pollMs
        }
    }

    private fun pendingFatalNow() = synchronized(lock) { pendingFatal }

    private fun switchTo(s: SonicSession, reason: RenewReason): Boolean {
        val old: SonicSession?
        val replayed: Int
        val gen: Int
        synchronized(lock) {
            if (pendingFatal || stopped.get() || pending !== s) { pending = null; s.close(); return false }
            old = active
            val replay = ring.drain()
            if (replay.isNotEmpty()) s.sendAudio(replay)
            replayed = replay.size
            active = s
            pending = null
            activeStartedMs = clockMs()
            lastActivityMs = activeStartedMs
            gen = ++generation
        }
        // The old session no longer gets Caller audio. Its late output is still forwarded until it closes.
        old?.end()
        onNotice(RenewNotice.Renewed(reason, gen, replayed))
        return true
    }
}
