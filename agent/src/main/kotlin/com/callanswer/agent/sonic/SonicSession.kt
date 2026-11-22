package com.callanswer.agent.sonic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** What a [SonicSession] reports to its caller. */
sealed interface SonicEvent {
    /** Caller speech recognised by Sonic (role USER). */
    data class CallerTranscript(val text: String) : SonicEvent

    /** Speculative assistant text; may be replaced or never spoken. */
    data class SpeculativeText(val text: String) : SonicEvent

    /** Final assistant text (arrives late, after the audio). */
    data class AgentTranscript(val text: String) : SonicEvent

    /** Agent audio, 24 kHz PCM16 mono. */
    class AgentAudio(val pcm: ByteArray) : SonicEvent

    data class ToolCalled(val toolName: String, val toolUseId: String, val args: String, val result: String, val timedOut: Boolean) : SonicEvent
    data class Usage(val inputSpeech: Int, val inputText: Int, val outputSpeech: Int, val outputText: Int) : SonicEvent
    data object UserSpeechStarted : SonicEvent
    data object UserSpeechEnded : SonicEvent

    /** The Caller barged in: the stop reason of the assistant TEXT block was `INTERRUPTED`. */
    data object Interrupted : SonicEvent

    /** `completionEnd` arrived. */
    data class Completed(val stopReason: String?) : SonicEvent

    /** The stream could not be opened or failed before any output arrived. */
    data class StartFailed(val message: String) : SonicEvent

    /** The stream failed after it was running (for example the 8-minute `ModelTimeoutException`). */
    data class StreamError(val message: String, val modelTimeout: Boolean) : SonicEvent

    /** The stream ended without an error and without [end] being called. */
    data object StreamEnded : SonicEvent
}

/** Runs one tool call. [args] is the parsed tool input. */
fun interface SonicToolRunner {
    suspend fun run(toolName: String, args: JsonObject): JsonObject
}

/**
 * One Nova 2 Sonic stream. Output events are parsed and reported through [events]; tool calls run with a
 * timeout of [toolTimeoutMs] and each `toolUse` gets exactly one `toolResult`. Silence is sent whenever no
 * Caller audio goes out for [silenceAfterMs], because Sonic closes a stream that gets no audio bytes for
 * about 55 seconds (spike C-5).
 */
class SonicSession(
    val ids: SessionIds,
    private val factory: SonicStreamFactory,
    private val tools: SonicToolRunner,
    private val scope: CoroutineScope,
    private val toolTimeoutMs: Long = 2_000,
    private val silenceAfterMs: Long = 200,
) : SonicStreamListener {
    private val eventChannel = Channel<SonicEvent>(Channel.UNLIMITED)
    val events: Flow<SonicEvent> = eventChannel.receiveAsFlow()

    private val sendLock = Any()
    private var stream: SonicStream? = null
    private val closed = AtomicBoolean(false)
    @Volatile private var sawOutput = false
    @Volatile private var lastAudioSentNs = System.nanoTime()
    private var keepAlive: Job? = null

    private class Block(val role: String, val type: String, val stage: String?)
    private val blocks = HashMap<String, Block>()
    private val answered = HashSet<String>()                    // toolUseIds that already got their result
    private val pendingTools = HashMap<String, SonicOutput.ToolUse>() // contentId -> toolUse

    private fun emit(e: SonicEvent) { eventChannel.trySend(e) }

    /**
     * Opens the stream and sends the start sequence (system prompt, history, audio block).
     * A failure is reported as [SonicEvent.StartFailed]; the return value tells whether the stream is open.
     */
    fun start(
        voiceId: String,
        systemPrompt: String,
        history: List<HistoryMessage> = emptyList(),
        tools: List<ToolSpec> = ToolSpecs.all,
    ): Boolean {
        try {
            stream = factory.open(this)
            SonicEvents.startSequence(ids, voiceId, systemPrompt, history, tools).forEach { send(it) }
        } catch (e: Exception) {
            fail(e, forceStart = true)
            return false
        }
        keepAlive = scope.launch {
            val silence = Base64.getEncoder().encodeToString(ByteArray(SILENCE_FRAME_BYTES))
            while (isActive && !closed.get()) {
                delay(SILENCE_FRAME_MS)
                if ((System.nanoTime() - lastAudioSentNs) / 1_000_000 >= silenceAfterMs) {
                    send(SonicEvents.audioInput(ids.promptName, ids.audioContentName, silence), audio = true)
                }
            }
        }
        return true
    }

    private fun send(event: String, audio: Boolean = false) {
        val s = stream ?: return
        if (closed.get()) return
        synchronized(sendLock) { s.send(event) }
        if (audio) lastAudioSentNs = System.nanoTime()
    }

    /** Sends several events as one uninterrupted group. */
    private fun sendAll(events: List<String>) {
        val s = stream ?: return
        if (closed.get()) return
        synchronized(sendLock) { events.forEach { s.send(it) } }
    }

    /** Sends 16 kHz PCM16 mono Caller audio. */
    fun sendAudio(pcm16k: ByteArray) {
        send(SonicEvents.audioInput(ids.promptName, ids.audioContentName, Base64.getEncoder().encodeToString(pcm16k)), audio = true)
    }

    /** Sends a server-to-model instruction as marked cross-modal text (`[[SERVER]] ...`). */
    fun instruct(instruction: String) {
        val text = if (instruction.startsWith(SonicEvents.SERVER_MARKER)) instruction else "${SonicEvents.SERVER_MARKER} $instruction"
        sendAll(SonicEvents.serverInstruction(ids, UUID.randomUUID().toString(), text))
    }

    fun sayExactly(sentence: String) = instruct(SonicEvents.sayExactly(sentence))

    fun greet(greeting: String) = instruct(SonicEvents.greetingTrigger(greeting))

    /** Ends the session in the documented order and closes the stream. */
    fun end() {
        if (closed.get()) return
        sendAll(SonicEvents.endSequence(ids))
        close()
    }

    /** Closes without sending the end sequence (for a stream that already failed). */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        keepAlive?.cancel()
        try { stream?.close() } catch (_: Exception) { }
        eventChannel.close()
    }

    // ---------------------------------------------------------------- listener

    override fun onEvent(text: String) {
        sawOutput = true
        when (val o = SonicEvents.parse(text)) {
            is SonicOutput.ContentStart -> synchronized(blocks) { blocks[o.contentId] = Block(o.role, o.type, o.generationStage) }
            is SonicOutput.TextOutput -> onText(o)
            is SonicOutput.AudioOutput -> emit(SonicEvent.AgentAudio(Base64.getDecoder().decode(o.base64)))
            is SonicOutput.ToolUse -> synchronized(pendingTools) { pendingTools[o.contentId] = o }
            is SonicOutput.ContentEnd -> onContentEnd(o)
            is SonicOutput.Usage -> emit(SonicEvent.Usage(o.inputSpeech, o.inputText, o.outputSpeech, o.outputText))
            is SonicOutput.UserSpeechStart -> emit(SonicEvent.UserSpeechStarted)
            is SonicOutput.UserSpeechEnd -> emit(SonicEvent.UserSpeechEnded)
            is SonicOutput.CompletionEnd -> emit(SonicEvent.Completed(o.stopReason))
            is SonicOutput.CompletionStart, is SonicOutput.Unknown -> Unit
        }
    }

    private fun onText(o: SonicOutput.TextOutput) {
        val block = synchronized(blocks) { blocks[o.contentId] }
        val role = block?.role ?: o.role
        if (isInterruptMarker(o.content)) return
        when {
            role == "USER" -> emit(SonicEvent.CallerTranscript(o.content))
            role == "ASSISTANT" && block?.stage == "SPECULATIVE" -> emit(SonicEvent.SpeculativeText(o.content))
            role == "ASSISTANT" -> emit(SonicEvent.AgentTranscript(o.content))
        }
    }

    private fun isInterruptMarker(content: String): Boolean = try {
        (Json.parseToJsonElement(content) as? JsonObject)?.get("interrupted") != null
    } catch (_: Exception) { false }

    private fun onContentEnd(o: SonicOutput.ContentEnd) {
        val tool = synchronized(pendingTools) { pendingTools.remove(o.contentId) }
        if (tool != null) {
            if (o.stopReason == "TOOL_USE" || o.type == "TOOL") runTool(tool)
            return
        }
        val block = synchronized(blocks) { blocks.remove(o.contentId) }
        if (o.interrupted && (block == null || block.role == "ASSISTANT")) emit(SonicEvent.Interrupted)
    }

    private fun runTool(use: SonicOutput.ToolUse) {
        scope.launch {
            val args = try {
                Json.parseToJsonElement(use.contentJson.ifBlank { "{}" }).jsonObject
            } catch (_: Exception) { null }
            var timedOut = false
            val result: JsonObject = if (args == null) {
                errorResult("BAD_ARGUMENTS")
            } else try {
                withTimeoutOrNull(toolTimeoutMs) { tools.run(use.toolName, args) }
                    ?: run { timedOut = true; errorResult("TIMEOUT") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorResult("EXCEPTION")
            }
            sendToolResult(use.toolUseId, result.toString())
            emit(SonicEvent.ToolCalled(use.toolName, use.toolUseId, use.contentJson, result.toString(), timedOut))
        }
    }

    private fun errorResult(code: String): JsonObject = buildJsonObject {
        put("status", JsonPrimitive("ERROR"))
        put("error", JsonPrimitive(code))
    }

    private fun sendToolResult(toolUseId: String, resultJson: String) {
        synchronized(answered) { if (!answered.add(toolUseId)) return }
        val cn = UUID.randomUUID().toString()
        sendAll(
            listOf(
                SonicEvents.toolContentStart(ids.promptName, cn, toolUseId),
                SonicEvents.toolResult(ids.promptName, cn, resultJson),
                SonicEvents.contentEnd(ids.promptName, cn),
            )
        )
    }

    override fun onError(t: Throwable) = fail(t, forceStart = false)

    override fun onClosed() {
        if (closed.get()) return
        emit(SonicEvent.StreamEnded)
        close()
    }

    private fun fail(t: Throwable, forceStart: Boolean) {
        if (closed.get()) return
        val message = describe(t)
        val timeout = generateSequence(t) { it.cause }.take(6).any { it.javaClass.simpleName == "ModelTimeoutException" }
        emit(if (forceStart || !sawOutput) SonicEvent.StartFailed(message) else SonicEvent.StreamError(message, timeout))
        close()
    }

    private fun describe(t: Throwable): String =
        generateSequence(t) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }.take(600)

    companion object {
        /** 32 ms of 16 kHz PCM16 mono. */
        const val SILENCE_FRAME_BYTES = 1024
        const val SILENCE_FRAME_MS = 32L
    }
}
