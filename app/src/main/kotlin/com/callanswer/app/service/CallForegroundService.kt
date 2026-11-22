package com.callanswer.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.callanswer.app.R
import com.callanswer.app.data.SettingsStore
import com.callanswer.core.engine.BaseConversationEngine
import com.callanswer.core.engine.CallSession
import com.callanswer.core.engine.ConversationEngine
import com.callanswer.core.engine.ConversationEvent
import com.callanswer.core.engine.ConversationState
import com.callanswer.core.engine.Effect
import com.callanswer.core.engine.EndedBy
import com.callanswer.core.engine.EngineContext
import com.callanswer.core.engine.Mode
import com.callanswer.core.engine.Phase
import com.callanswer.core.engine.TakeoverOrigin
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.LogNoteKind
import com.callanswer.core.model.VoiceSubstitution
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Receives the engine effects of a call. The pipeline tasks (ASR, TTS, player, transfer) plug in here. */
fun interface EffectSink {
    fun handle(session: CallSession, effect: Effect)
}

/**
 * Owns one call session at a time: a [CallSession] with its [FrozenSettings] snapshot, the engine state,
 * and the [LiveCallUiState] flow. The notification is shown immediately on start (13.19). Control intents
 * map to engine events stamped with the time the intent was received (13.4, 13.21).
 */
class CallForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine: ConversationEngine = BaseConversationEngine()
    private val lock = Any()
    private var session: CallSession? = null
    private var engineState = ConversationState()
    private var startElapsedMs = 0L
    private var pipeline: CallPipeline? = null
    private var executor: EffectExecutor? = null
    private var finishing = false
    private var callEndWatcher: CallEndWatcher? = null
    private val trace = CallTraceRecorder()
    @Volatile private var traceSessionId: String? = null

    /** Log notes raised by effects during the call; read by the call-end finalization. */
    private val logNotes = java.util.concurrent.CopyOnWriteArrayList<LogNoteKind>()
    private val voiceSubstitutions = java.util.concurrent.CopyOnWriteArrayList<VoiceSubstitution>()

    /** Set while dispatching: the reason of the engine's `EndCall`, or who ended the call from outside. */
    @Volatile private var explicitEndReason: EndReason? = null
    @Volatile private var endedBy: EndedBy? = null
    @Volatile private var endedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_TAKE_OVER -> dispatch { ConversationEvent.UserTakeOver(it, TakeoverOrigin.ALERT) }
            ACTION_TAKE_OVER_UI -> dispatch { ConversationEvent.UserTakeOver(it, TakeoverOrigin.LIVE_CALL_SCREEN) }
            ACTION_MUTE -> dispatch { ConversationEvent.UserMute(it) }
            ACTION_RESUME -> dispatch { ConversationEvent.UserResume(it) }
            ACTION_END -> dispatch { ConversationEvent.UserEndCall(it) }
            ACTION_QUICK_REPLY -> intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { text -> dispatch { ConversationEvent.QuickReplySent(it, text) } }
            ACTION_EXPORT_TRACE -> {
                exportTrace()
                if (session == null) stopSelf(startId)
            }
            ACTION_CALL_ENDED -> {
                val by = runCatching { EndedBy.valueOf(intent.getStringExtra(EXTRA_ENDED_BY).orEmpty()) }
                    .getOrDefault(EndedBy.CALLER)
                dispatch { ConversationEvent.CallEnded(it, by) }
            }
            else -> if (session == null) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startSession(intent: Intent) {
        if (session != null) return
        val number = intent.getStringExtra(EXTRA_NUMBER)
        val id = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString()
        val json = intent.getStringExtra(EXTRA_FROZEN_JSON)
        // Show the notification first so the 2 s limit holds even when the snapshot has to be read.
        enterForeground(buildNotification(number, _uiState.value.copy(active = true, callerNumber = number)))
        finishing = false
        logNotes.clear()
        voiceSubstitutions.clear()
        explicitEndReason = null
        endedBy = null
        startElapsedMs = SystemClock.elapsedRealtime()
        trace.clear()
        traceSessionId = id
        val startedAt = Instant.now()
        _uiState.value = LiveCallUiState(active = true, callerNumber = number)
        scope.launch {
            val frozen = json?.let { runCatching { CoreJson.decodeFromString<FrozenSettings>(it) }.getOrNull() }
                ?: runCatching { SettingsStore.create(applicationContext).snapshot() }
                    .getOrDefault(FrozenSettings())
            val s = CallSession(id, number, startedAt, ZoneId.systemDefault(), frozen)
            synchronized(lock) { session = s; engineState = ConversationState() }
            _uiState.update { it.copy(quickReplies = frozen.quickReplies) }
            // Gap from 29.3: nothing reported a disconnect, so a hang-up by the Caller never finalized.
            callEndWatcher?.stop()
            callEndWatcher = CallEndWatcher(applicationContext) {
                dispatch { ConversationEvent.CallEnded(it, EndedBy.CALLER) }
            }.also { it.start() }
            val p = CallPipeline(applicationContext, scope, frozen, s.zone, emit = { ev -> dispatch(ev) })
            pipeline = p
            currentPipeline = p
            executor = EffectExecutor.create(
                applicationContext, scope, s, p,
                emit = { ev -> dispatch(ev) },
                history = { synchronized(lock) { engineState.transcript } },
                logNote = { logNotes += it },
                onVoiceSubstitution = { voiceSubstitutions += it },
            )
            p.routeAudio()
            dispatch { ConversationEvent.CallAnswered(it) }
            p.startListening()
        }
    }

    /** Applies [event] to the engine, publishes the state and routes the effects. Safe to call from any thread. */
    private fun dispatch(event: (Long) -> ConversationEvent) {
        val atMs = SystemClock.elapsedRealtime() - startElapsedMs
        var ended = false
        var endsCall = false
        val s: CallSession
        val effects: List<Effect>
        synchronized(lock) {
            s = session ?: return
            val ctx = EngineContext(
                frozen = s.frozen,
                callerKnownNumber = s.callerNumber != null,
                callerNumber = s.callerNumber,
                callStartEpochMs = s.startedAt.toEpochMilli(),
                zoneId = s.zone.id,
            )
            val ev = event(atMs)
            trace.event(atMs, ev)
            val t = runCatching { engine.reduce(engineState, ev, ctx) }.getOrNull() ?: return
            // The engine leaves most phases unchanged on `CallEnded`; the call is over in every case.
            val callOver = ev is ConversationEvent.CallEnded
            engineState = if (callOver && t.state.phase != Phase.ENDED) t.state.copy(phase = Phase.ENDED) else t.state
            effects = t.effects
            ended = engineState.phase == Phase.ENDED
            if (ended && !finishing) {
                endedAtMs = ev.atMs
                if (ev is ConversationEvent.CallEnded) endedBy = ev.by
                if (ev is ConversationEvent.UserEndCall) endedBy = EndedBy.USER
            }
        }
        runCatching { publish(s) }
        effects.forEach { e ->
            if (e is Effect.RaiseTakeoverAlert) _uiState.update { it.copy(takeoverAlertShown = true) }
            if (e is Effect.ClearTakeoverAlert) _uiState.update { it.copy(takeoverAlertShown = false) }
            if (e is Effect.EndCall) {
                endsCall = true
                if (!finishing && explicitEndReason == null) explicitEndReason = e.reason
            }
            trace.effect(SystemClock.elapsedRealtime() - startElapsedMs, e)
            runCatching { executor?.handle(s, e) }
            runCatching { effectSink.handle(s, e) }
        }
        if (ended) finishSession(drainSpeech = endsCall)
    }

    private fun publish(s: CallSession) {
        val st = engineState
        val ui = _uiState.updateAndGet {
            it.copy(
                active = st.phase != Phase.ENDED,
                callerNumber = s.callerNumber,
                phase = st.phase,
                mode = st.mode,
                intent = st.intent,
                language = st.lastLanguage,
                transcript = st.transcript,
            )
        }
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(s.callerNumber, ui))
    }

    /**
     * Ends the session. When the engine ended the call ([drainSpeech]), the queued goodbye is played and the call
     * is ended first (at most [DRAIN_MS]); a hang-up by the Caller stops the speech at once.
     */
    private fun finishSession(drainSpeech: Boolean) {
        if (finishing) return
        finishing = true
        callEndWatcher?.stop()
        callEndWatcher = null
        // Captured now: a later start must not see this call's state.
        val finished: FinishedCall? = synchronized(lock) {
            session?.let { s ->
                FinishedCall(
                    session = s,
                    state = engineState,
                    endedAtMs = endedAtMs,
                    endReason = CallOutcomeResolver.endReason(engineState, explicitEndReason, endedBy),
                    notes = logNotes.toList(),
                    voiceSubstitutions = voiceSubstitutions.toList(),
                )
            }
        }
        // Debug builds write the trace of every call so it can be pulled with adb after the call.
        if (isDebuggable()) exportTrace()
        scope.launch {
            val ex = executor
            if (ex != null && drainSpeech) runCatching { ex.drain(DRAIN_MS) }
            // Clears the Takeover_Alert, stops the speech and releases the synthesizer.
            runCatching { ex?.close() }
            executor = null
            // Releases the ASR/VAD models and restores the call volume.
            pipeline?.stop()
            pipeline = null
            currentPipeline = null
            synchronized(lock) { session = null }
            // Saved after the call has ended and the audio is released; the save retries inside the DAO (10.3).
            if (finished != null) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        CallFinalizer(applicationContext).finalize(finished)
                    }
                }
            }
            _uiState.update { it.copy(active = false, takeoverAlertShown = false) }
            ServiceCompat.stopForeground(this@CallForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun isDebuggable() =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Debug action (34.3): writes the event and effect timeline of the current or last call to
     * `Android/data/<package>/files/traces/trace-<session>.tsv`. Does nothing in release builds.
     */
    private fun exportTrace() {
        if (!isDebuggable()) return
        runCatching {
            val dir = java.io.File(getExternalFilesDir(null), "traces").apply { mkdirs() }
            java.io.File(dir, "trace-${traceSessionId ?: "none"}.tsv").writeText(trace.export(traceSessionId))
        }
    }

    private fun enterForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun buildNotification(number: String?, ui: LiveCallUiState): Notification {
        val label = number ?: getString(R.string.call_notif_unknown_number)
        val muted = ui.mode != Mode.AGENT_ACTIVE
        val builder = NotificationCompat.Builder(this, NotificationChannels.LIVE_CALL)
            .setSmallIcon(com.callanswer.app.R.drawable.ic_stat_call)
            .setContentTitle(getString(R.string.call_notif_title, label))
            .setContentText(getString(R.string.call_notif_text, ui.intent.name, ui.language?.name ?: "-"))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(NotificationCenter(this).liveCallPendingIntent())
            .setFullScreenIntent(NotificationCenter(this).liveCallPendingIntent(), true)
            // Order is fixed: Take over, Mute/Resume, End call.
            .addAction(0, getString(R.string.call_action_take_over), controlIntent(this, ACTION_TAKE_OVER, 11))
            .addAction(
                0,
                getString(if (muted) R.string.call_action_resume else R.string.call_action_mute),
                controlIntent(this, if (muted) ACTION_RESUME else ACTION_MUTE, 12),
            )
            .addAction(0, getString(R.string.call_action_end), controlIntent(this, ACTION_END, 13))
        return builder.build()
    }

    override fun onDestroy() {
        callEndWatcher?.stop()
        callEndWatcher = null
        executor?.close()
        executor = null
        pipeline?.stop()
        currentPipeline = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.callanswer.app.action.START_CALL"
        const val ACTION_TAKE_OVER = "com.callanswer.app.action.TAKE_OVER"
        const val ACTION_TAKE_OVER_UI = "com.callanswer.app.action.TAKE_OVER_UI"
        const val ACTION_MUTE = "com.callanswer.app.action.MUTE_AGENT"
        const val ACTION_RESUME = "com.callanswer.app.action.RESUME_AGENT"
        const val ACTION_END = "com.callanswer.app.action.END_CALL"

        /** Debug builds only: write the call trace (event and effect timeline) to the app's external files dir. */
        const val ACTION_EXPORT_TRACE = "com.callanswer.app.action.EXPORT_TRACE"

        /** Speak a typed or tapped Quick_Reply; carries [EXTRA_TEXT] (13.14). */
        const val ACTION_QUICK_REPLY = "com.callanswer.app.action.QUICK_REPLY"
        const val EXTRA_TEXT = "text"

        /** The call ended (Caller hung up or the system disconnected it). Carries [EXTRA_ENDED_BY]. */
        const val ACTION_CALL_ENDED = "com.callanswer.app.action.CALL_ENDED"
        const val EXTRA_ENDED_BY = "endedBy"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_FROZEN_JSON = "frozenSettings"
        const val NOTIFICATION_ID = 1001
        private const val DRAIN_MS = 15_000L
        const val FALLBACK_NOTIFICATION_ID = 1002

        private val _uiState = MutableStateFlow(LiveCallUiState())
        /** State of the current call for the Live_Call_Screen. */
        val uiState: StateFlow<LiveCallUiState> = _uiState.asStateFlow()

        /** The audio pipeline of the current call (EchoGate, network quality, agent-utterance reporting), or null. */
        @Volatile var currentPipeline: CallPipeline? = null
            private set

        /** Where engine effects go. Replaced by the pipeline wiring in later tasks. */
        @Volatile var effectSink: EffectSink = EffectSink { _, _ -> }

        fun controlIntent(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getService(
                context, requestCode,
                Intent(context, CallForegroundService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun callEndedIntent(context: Context, by: EndedBy = EndedBy.CALLER): Intent =
            Intent(context, CallForegroundService::class.java).setAction(ACTION_CALL_ENDED)
                .putExtra(EXTRA_ENDED_BY, by.name)

        fun startIntent(context: Context, number: String?, frozen: FrozenSettings?): Intent =
            Intent(context, CallForegroundService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_SESSION_ID, UUID.randomUUID().toString())
                .apply { frozen?.let { putExtra(EXTRA_FROZEN_JSON, CoreJson.encodeToString(it)) } }

        /**
         * Starts the service from the call-answer path. When the system refuses a background start
         * (Android 12+ `ForegroundServiceStartNotAllowedException`), posts a full-screen-intent notification
         * whose tap/launch opens the Live_Call_Screen and starts the service from that interaction (D2, R5).
         * Returns true when the direct start worked.
         */
        fun startOrFallback(context: Context, number: String?, frozen: FrozenSettings?): Boolean {
            val intent = startIntent(context, number, frozen)
            return try {
                context.startForegroundService(intent)
                true
            } catch (e: Exception) {
                val refused = Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException ||
                    e is IllegalStateException
                if (!refused) throw e
                postFallback(context, number, intent)
                false
            }
        }

        private fun postFallback(context: Context, number: String?, startIntent: Intent) {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val startPi = PendingIntent.getForegroundService(context, 20, startIntent, flags)
            val label = number ?: context.getString(R.string.call_notif_unknown_number)
            val n = NotificationCompat.Builder(context, NotificationChannels.TAKEOVER_ALERT)
                .setSmallIcon(com.callanswer.app.R.drawable.ic_stat_call)
                .setContentTitle(context.getString(R.string.call_fallback_title))
                .setContentText(context.getString(R.string.call_fallback_text, label))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(startPi)
                .setFullScreenIntent(NotificationCenter(context).liveCallPendingIntent(), true)
                .build()
            context.getSystemService(android.app.NotificationManager::class.java)
                .notify(FALLBACK_NOTIFICATION_ID, n)
        }
    }
}
