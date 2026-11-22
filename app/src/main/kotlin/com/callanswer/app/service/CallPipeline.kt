package com.callanswer.app.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.callanswer.app.asr.SpeechRecognizerPort
import com.callanswer.app.asr.createSpeechRecognizer
import com.callanswer.app.CallLog
import com.callanswer.app.a11y.A11yToggle
import com.callanswer.app.audio.AndroidAudioRouteController
import com.callanswer.app.audio.AudioRecordCallAudioCapture
import com.callanswer.app.audio.AudioRouteController
import com.callanswer.app.audio.CallAudioCaptureOwner
import com.callanswer.app.audio.CallAudioCapture
import com.callanswer.app.audio.EchoGate
import com.callanswer.app.audio.SpeechSegment
import com.callanswer.app.audio.SpeechSegmenter
import com.callanswer.app.audio.VoiceActivityDetector
import com.callanswer.app.audio.createVoiceActivityDetector
import com.callanswer.app.net.NetworkMonitor
import com.callanswer.core.echo.AgentUtterance
import com.callanswer.core.echo.AsrSegment
import com.callanswer.core.echo.EchoTextFilter
import com.callanswer.core.engine.ConversationEvent
import com.callanswer.core.language.ScriptLanguageDetector
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalTime
import java.time.ZoneId

/**
 * The audio path of one call: speaker routing, capture, VAD, [EchoGate], ASR, [EchoTextFilter] and language
 * detection, turned into engine events through [emit], plus the 1 s `Tick` loop and the [NetworkMonitor].
 *
 * Clocks: frame, segment, [EchoGate] and [AgentUtterance] times use `SystemClock.elapsedRealtime()`. The
 * engine clock is that time minus the call start; [emit] receives a builder that is given the engine time.
 * Every exception is caught here and becomes an engine event (or an empty result); none reaches the service.
 */
class CallPipeline(
    private val context: Context,
    private val scope: CoroutineScope,
    private val frozen: FrozenSettings,
    private val zone: ZoneId,
    private val emit: ((Long) -> ConversationEvent) -> Unit,
    private val recognizerFactory: () -> SpeechRecognizerPort = { createSpeechRecognizer(context) },
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    /** Shared with the audio player (task 29.2): mark every Agent playback and alert tone here. */
    val echoGate = EchoGate()
    val network = NetworkMonitor(context)

    private val route: AudioRouteController = AndroidAudioRouteController(
        context,
        NotificationCenter(context),
    ) { frozen.speakerEnabled }
    private val detector = ScriptLanguageDetector.withBundledLexicon()
    private val agentUtterances = ArrayList<AgentUtterance>()
    private val segments = Channel<SpeechSegment>(Channel.UNLIMITED)
    private val startMs = clockMs()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val audioDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var vad: VoiceActivityDetector? = null
    private var recognizer: SpeechRecognizerPort? = null
    private var segmenter: SpeechSegmenter? = null
    private val jobs = ArrayList<Job>()

    @Volatile private var lastAgentEndMs = startMs
    @Volatile private var lastLanguage: Language? = null
    @Volatile private var lastUnusableMs = 0L
    @Volatile private var stopped = false
    @Volatile private var routeApplied = false
    @Volatile private var captureStarted = false
    @Volatile private var captureRef: CallAudioCapture? = null

    /** Applies the speaker policy. Never throws (13.1, 13.2). */
    suspend fun routeAudio() {
        try {
            val on = route.enableSpeaker()
            CallLog.i("speaker route applied: speakerOn=$on audioMode=${AudioRecordCallAudioCapture.modeName(audioMode())}")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "speaker routing failed", t)
        } finally {
            routeApplied = true
        }
    }

    /** Starts the network monitor, capture, the ASR consumer and the Tick loop. */
    fun startListening() {
        // The silence count starts at the answer, not at pipeline creation (the call may have been ringing).
        lastAgentEndMs = clockMs()
        runCatching { network.start(scope) }.onFailure { Log.w(TAG, "network monitor failed", it) }
        val seg = SpeechSegmenter(
            vad = createVad().also { vad = it },
            gate = { s, e -> echoGate.isFullyCovered(s, e) },
            startMs = clockMs(),
            onVoiceActivity = { emit { at -> ConversationEvent.VoiceActivity(at) } },
            onSegment = { segments.trySend(it) },
        )
        segmenter = seg
        jobs += scope.launch(Dispatchers.IO) { consumeSegments() }
        jobs += scope.launch(audioDispatcher) { tickLoop(seg) }
        jobs += scope.launch(audioDispatcher) { startCapture(seg) }
    }

    /**
     * The microphone foreground type was granted after capture started. A silenced `AudioRecord` does not recover
     * by itself, so it is recreated unless audio is already flowing.
     */
    fun onMicrophoneTypeGranted() {
        val seg = segmenter ?: return
        if (captureStarted && !seg.heardAudio) CallAudioCaptureOwner.restart()
    }

    /**
     * Starts capture only when the call is OFFHOOK (or in call audio mode), the speaker route is applied and the
     * microphone foreground type is held. The first two are required: before the call is answered the platform
     * hands the app silence. The third is waited for [MIC_TYPE_WAIT_MS] and then capture starts anyway, so the
     * upgrade path ([onMicrophoneTypeGranted]) can restart it.
     */
    private suspend fun startCapture(seg: SpeechSegmenter) {
        val active = waitUntil(CALL_ACTIVE_WAIT_MS) { callIsActive() }
        CallLog.i("capture gate: callActive=$active audioMode=${AudioRecordCallAudioCapture.modeName(audioMode())} speakerRouteApplied=$routeApplied")
        // The route set before the answer can be reset by the telephony stack: apply it again now.
        if (active) routeAudio()
        waitUntil(ROUTE_WAIT_MS) { routeApplied }
        if (A11yToggle.enabledByApp) {
            val bound = A11yToggle.awaitConnected(A11yToggle.CONNECT_WAIT_MS)
            CallLog.i("capture gate: accessibility service connected=$bound")
        }
        val micType = waitUntil(MIC_TYPE_WAIT_MS) { CallForegroundService.micTypeActive }
        CallLog.i("capture gate: micTypeActive=$micType, starting capture")
        val capture = runCatching {
            CallAudioCaptureOwner.start(context) { NotificationCenter(context).captureSilenced() }
        }.getOrNull()
        captureStarted = capture != null
        if (capture == null) return
        captureRef = capture
        collectFrames(capture, seg)
    }

    private fun audioMode(): Int = CallSample.read(context).mode

    private fun callIsActive(): Boolean = CallSample.read(context).active

    private suspend fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = clockMs() + timeoutMs
        while (!cond()) {
            if (clockMs() >= end || stopped) return cond()
            delay(100)
        }
        return true
    }

    /** Called by the effect executor when an Agent utterance finished playing. Restarts the silence count (5.6). */
    fun noteAgentUtterance(text: String, ttsStartMs: Long, ttsEndMs: Long) {
        lastAgentEndMs = ttsEndMs
        runCatching {
            echoGate.markPlayback(ttsStartMs, ttsEndMs)
            synchronized(agentUtterances) {
                agentUtterances += AgentUtterance(text, ttsStartMs, ttsEndMs)
                while (agentUtterances.size > MAX_UTTERANCES) agentUtterances.removeAt(0)
            }
        }
    }

    /** Stops everything and releases the models and the volume. Safe to call twice. */
    fun stop() {
        if (stopped) return
        stopped = true
        jobs.forEach { it.cancel() }
        segments.close()
        runCatching { segmenter?.flush() }
        runCatching { CallAudioCaptureOwner.stop() }
        runCatching { network.stop() }
        runCatching { vad?.release() }
        runCatching { recognizer?.release() }
        runCatching { route.release() }
        echoGate.clear()
    }

    private suspend fun collectFrames(capture: CallAudioCapture, seg: SpeechSegmenter) {
        try {
            capture.frames.collect { frame ->
                try {
                    seg.onFrame(frame)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "frame processing failed", t)
                    reportUnusable()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed", t)
            reportUnusable()
        }
    }

    private suspend fun tickLoop(seg: SpeechSegmenter) {
        while (scope.isActive && !stopped) {
            delay(TICK_MS)
            try {
                val now = clockMs()
                val t = seg.tick(now)
                // The silence count restarts after each Agent utterance (BaseConversationEngine.onTick).
                val silent = minOf(t.silentForMs, (now - lastAgentEndMs).coerceAtLeast(0))
                // Our own capture being silenced or never delivering is not a call interruption (8.8 applies
                // only to a real loss after audio had been flowing); the engine then works on its silence timers.
                val ownCaptureDown = captureRef?.silenced?.value ?: true
                val noAudio = if (ownCaptureDown) 0L else t.noAudioForMs
                emit { at -> t.copy(atMs = at, silentForMs = silent, noAudioForMs = noAudio) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "tick failed", t)
            }
        }
    }

    private suspend fun consumeSegments() {
        for (segment in segments) {
            try {
                handleSegment(segment)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // An ASR failure is an empty result: the engine asks the Caller to repeat once (2.10).
                Log.w(TAG, "segment handling failed", t)
                emit { ConversationEvent.CallerUtterance(it, "", null, localTime()) }
            }
        }
    }

    private suspend fun handleSegment(segment: SpeechSegment) {
        val rec = recognizer ?: runCatching { recognizerFactory() }.getOrNull()?.also { recognizer = it }
        val result = rec?.transcribe(segment, lastLanguage)
        val text = result?.text.orEmpty().trim()
        if (text.isNotEmpty()) {
            val agent = synchronized(agentUtterances) { agentUtterances.toList() }
            val kept = EchoTextFilter.filter(listOf(AsrSegment(text, segment.startMs, segment.endMs)), agent)
            if (kept.isEmpty()) return // the Agent's own voice
        }
        // The script detector is the primary language source; the ASR tag is the fallback (the engine applies the same order).
        val tag = result?.languageTag
        lastLanguage = if (text.isEmpty()) lastLanguage else detector.detect(text) ?: tag ?: lastLanguage
        emit { ConversationEvent.CallerUtterance(it, text, tag, localTime()) }
    }

    private fun reportUnusable() {
        val now = clockMs()
        if (now - lastUnusableMs < TICK_MS) return
        lastUnusableMs = now
        emit { ConversationEvent.CallerAudioUnusable(it) }
    }

    /** Device local time truncated to the minute; null when it cannot be read (11.5). */
    private fun localTime(): LocalTimeHm? = runCatching {
        val t = LocalTime.now(zone)
        LocalTimeHm(t.hour, t.minute)
    }.getOrNull()

    private fun createVad(): VoiceActivityDetector {
        val model = runCatching { vadModelFile() }.getOrNull()
        return createVoiceActivityDetector(model)
    }

    /** Copies the bundled Silero model out of the assets once; null when it is not bundled (energy VAD is used). */
    private fun vadModelFile(): File? {
        val dir = "models/vad"
        val name = context.assets.list(dir)?.firstOrNull { it.endsWith(".onnx") } ?: return null
        val target = File(context.filesDir, "vad-$name")
        if (!target.isFile) context.assets.open("$dir/$name").use { i -> target.outputStream().use { i.copyTo(it) } }
        return target
    }

    private companion object {
        const val TAG = "CallPipeline"
        const val TICK_MS = 1000L
        const val MAX_UTTERANCES = 32
        const val CALL_ACTIVE_WAIT_MS = 20_000L
        const val ROUTE_WAIT_MS = 3_000L
        const val MIC_TYPE_WAIT_MS = 2_000L
    }
}
