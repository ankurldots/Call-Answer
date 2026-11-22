package com.callanswer.spikecloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamInput
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamResponseHandler
import java.io.File
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import org.reactivestreams.Publisher
import org.reactivestreams.Subscription

const val MODEL_ID = "amazon.nova-2-sonic-v1:0"
const val REGION = "ap-northeast-1"
const val PROFILE = "ldots"

fun buildClient(): BedrockRuntimeAsyncClient = BedrockRuntimeAsyncClient.builder()
    .region(Region.of(REGION))
    .credentialsProvider(ProfileCredentialsProvider.create(PROFILE))
    .httpClientBuilder(
        NettyNioAsyncHttpClient.builder()
            .connectionAcquisitionTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofMinutes(15))
            .writeTimeout(Duration.ofMinutes(15))
            .connectionTimeout(Duration.ofSeconds(15))
    )
    .build()

/** One received output event with its time (ms since the session object was created). */
class Ev(val tMs: Long, val kind: String, val obj: JsonObject) {
    val body: JsonObject get() = obj["event"]!!.jsonObject.values.first().jsonObject
    fun str(key: String): String? = (body[key] as? JsonPrimitive)?.contentOrNull
}

/** Block metadata taken from contentStart. */
class Block(val id: String, val type: String?, val role: String?, val stage: String?, val startMs: Long)

private val json = Json { ignoreUnknownKeys = true }
private val b64 = Regex("\"content\"\\s*:\\s*\"([A-Za-z0-9+/=]{200,})\"")

fun escapeJson(s: String): String = JsonPrimitive(s).toString()

class SonicSession(
    val name: String,
    private val client: BedrockRuntimeAsyncClient,
    private val runDir: File,
    private val scope: CoroutineScope,
    private val frames: Frames? = null,
    val toolHandler: ((String, String) -> String)? = null,
) {
    private val t0 = System.nanoTime()
    fun nowMs(): Long = (System.nanoTime() - t0) / 1_000_000
    val promptName = UUID.randomUUID().toString()
    val events = CopyOnWriteArrayList<Ev>()
    val blocks = HashMap<String, Block>()
    private val outbox = Channel<InvokeModelWithBidirectionalStreamInput>(Channel.UNLIMITED)
    private val rawLog = runDir.apply { mkdirs() }.resolve("$name.events.jsonl").bufferedWriter()
    @Volatile var lastActivityMs = -1L
    @Volatile var lastAudioMs = -1L
    @Volatile var finishedMs = -1L
    @Volatile var error: String? = null
    @Volatile var streamEnded = false
    var audioOutBytes = 0L
    private var audioFramesRecorded = 0
    lateinit var future: CompletableFuture<Void>
    val toolTimings = CopyOnWriteArrayList<Map<String, Any?>>()
    private val toolInfo = HashMap<String, Pair<String, String>>()               // contentId -> (name, input)
    private val toolUseIds = HashMap<String, String>()

    private fun raw(dir: String, text: String) {
        val trimmed = b64.replace(text) { "\"content\":\"<b64 ${it.groupValues[1].length} chars>\"" }
        synchronized(rawLog) { rawLog.write("{\"t\":${nowMs()},\"dir\":\"$dir\",\"event\":$trimmed}\n"); rawLog.flush() }
    }

    private fun recordFrame(dir: String, text: String, isAudio: Boolean) {
        val f = frames ?: return
        if (isAudio) {
            if (audioFramesRecorded >= 4) return
            audioFramesRecorded++
            f.record(dir, b64.replace(text) { m -> "\"content\":\"${m.groupValues[1].take(96)}\"" })
        } else f.record(dir, text)
    }

    fun send(text: String) {
        val isAudio = text.contains("\"audioInput\"")
        if (!isAudio) raw("in", text) else if (events.size % 1 == 0) rawAudioIn(text)
        recordFrame("in", text, isAudio)
        outbox.trySend(
            InvokeModelWithBidirectionalStreamInput.chunkBuilder().bytes(SdkBytes.fromUtf8String(text)).build()
        )
    }

    private var audioInCount = 0
    private fun rawAudioIn(text: String) {
        // log only every 50th audio frame (1.6 s) to keep the log small
        if (audioInCount++ % 50 == 0) raw("in", text)
    }

    fun sendAudio(pcm: ByteArray, contentName: String) {
        send(
            "{\"event\":{\"audioInput\":{\"promptName\":\"$promptName\",\"contentName\":\"$contentName\"," +
                "\"content\":\"${Base64.getEncoder().encodeToString(pcm)}\"}}}"
        )
    }

    fun open() {
        val publisher = Publisher<InvokeModelWithBidirectionalStreamInput> { sub ->
            val demand = Channel<Long>(Channel.UNLIMITED)
            sub.onSubscribe(object : Subscription {
                override fun request(n: Long) { demand.trySend(n) }
                override fun cancel() { demand.close() }
            })
            scope.launch(Dispatchers.IO) {
                try {
                    for (n in demand) {
                        var left = n
                        while (left > 0) {
                            val r = outbox.receiveCatching()
                            if (r.isClosed) { sub.onComplete(); return@launch }
                            sub.onNext(r.getOrThrow())
                            if (left != Long.MAX_VALUE) left--
                        }
                    }
                } catch (_: Throwable) { }
            }
        }
        val visitor = InvokeModelWithBidirectionalStreamResponseHandler.Visitor.builder()
            .onChunk { chunk -> onOutput(chunk.bytes().asUtf8String()) }
            .build()
        val handler = InvokeModelWithBidirectionalStreamResponseHandler.builder()
            .onResponse { }
            .onError { t -> markError(t) }
            .subscriber(visitor)
            .build()
        val req = InvokeModelWithBidirectionalStreamRequest.builder().modelId(MODEL_ID).build()
        future = client.invokeModelWithBidirectionalStream(req, publisher, handler)
        future.whenComplete { _, t ->
            if (t != null) markError(t)
            streamEnded = true
            if (finishedMs < 0) finishedMs = nowMs()
        }
    }

    private fun markError(t: Throwable) {
        if (System.getenv("C5_TRACE") != null) t.printStackTrace()
        if (error == null) {
            var c: Throwable? = t
            val parts = ArrayList<String>()
            while (c != null && parts.size < 4) { parts.add("${c.javaClass.simpleName}: ${c.message}"); c = c.cause }
            error = parts.joinToString(" <- ").take(600)
        }
        if (finishedMs < 0) finishedMs = nowMs()
    }

    private fun onOutput(text: String) {
        val t = nowMs()
        val isAudio = text.contains("\"audioOutput\"")
        raw("out", text)
        recordFrame("out", text, isAudio)
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { return }
        val inner = obj["event"]?.jsonObject ?: return
        val kind = inner.keys.first()
        val ev = Ev(t, kind, obj)
        when (kind) {
            "contentStart" -> {
                val stage = ev.str("additionalModelFields")?.let {
                    try { json.parseToJsonElement(it).jsonObject["generationStage"]?.jsonPrimitive?.contentOrNull } catch (_: Exception) { null }
                }
                val id = ev.str("contentId") ?: "?"
                synchronized(blocks) { blocks[id] = Block(id, ev.str("type"), ev.str("role"), stage, t) }
            }
            "audioOutput" -> {
                audioOutBytes += (ev.str("content")?.length ?: 0) * 3L / 4
                lastActivityMs = t
                lastAudioMs = t
            }
            "textOutput" -> lastActivityMs = t
            "toolUse" -> {
                val id = ev.str("contentId") ?: "?"
                toolInfo[id] = (ev.str("toolName") ?: "?") to (ev.str("content") ?: "{}")
                toolUseIds[id] = ev.str("toolUseId") ?: "?"
                toolTimings.add(mapOf("tool" to ev.str("toolName"), "toolUseMs" to t))
            }
            "contentEnd" -> {
                val id = ev.str("contentId") ?: "?"
                if (toolInfo.containsKey(id) && ev.str("stopReason") == "TOOL_USE") answerTool(id)
            }
        }
        events.add(ev)
    }

    private fun answerTool(contentId: String) {
        val (tool, input) = toolInfo[contentId] ?: return
        val toolUseId = toolUseIds[contentId] ?: return
        val handler = toolHandler ?: return
        val result = try { handler(tool, input) } catch (e: Exception) { "{\"status\":\"ERROR\",\"error\":\"EXCEPTION\"}" }
        val cn = UUID.randomUUID().toString()
        send(
            "{\"event\":{\"contentStart\":{\"promptName\":\"$promptName\",\"contentName\":\"$cn\",\"interactive\":false," +
                "\"type\":\"TOOL\",\"role\":\"TOOL\",\"toolResultInputConfiguration\":{\"toolUseId\":\"$toolUseId\"," +
                "\"type\":\"TEXT\",\"textInputConfiguration\":{\"mediaType\":\"text/plain\"}}}}}"
        )
        send("{\"event\":{\"toolResult\":{\"promptName\":\"$promptName\",\"contentName\":\"$cn\",\"content\":${escapeJson(result)}}}}")
        send("{\"event\":{\"contentEnd\":{\"promptName\":\"$promptName\",\"contentName\":\"$cn\"}}}")
        toolTimings.add(mapOf("tool" to tool, "input" to input, "resultSentMs" to nowMs(), "afterUseEndMs" to nowMs()))
    }

    // ---- input builders -------------------------------------------------------------------

    fun sessionStart(sensitivity: String = "MEDIUM") = send(
        "{\"event\":{\"sessionStart\":{\"inferenceConfiguration\":{\"maxTokens\":2048,\"topP\":0.9,\"temperature\":0.7}," +
            "\"turnDetectionConfiguration\":{\"endpointingSensitivity\":\"$sensitivity\"}}}}"
    )

    fun promptStart(voice: String, toolsJson: String) = send(
        "{\"event\":{\"promptStart\":{\"promptName\":\"$promptName\",\"textOutputConfiguration\":{\"mediaType\":\"text/plain\"}," +
            "\"audioOutputConfiguration\":{\"mediaType\":\"audio/lpcm\",\"sampleRateHertz\":24000,\"sampleSizeBits\":16," +
            "\"channelCount\":1,\"voiceId\":\"$voice\",\"encoding\":\"base64\",\"audioType\":\"SPEECH\"}," +
            "\"toolUseOutputConfiguration\":{\"mediaType\":\"application/json\"}," +
            "\"toolConfiguration\":{\"toolChoice\":{\"auto\":{}},\"tools\":$toolsJson}}}}"
    )

    fun textBlock(role: String, text: String, interactive: Boolean = false) {
        val cn = UUID.randomUUID().toString()
        send(
            "{\"event\":{\"contentStart\":{\"promptName\":\"$promptName\",\"contentName\":\"$cn\",\"type\":\"TEXT\"," +
                "\"interactive\":$interactive,\"role\":\"$role\",\"textInputConfiguration\":{\"mediaType\":\"text/plain\"}}}}"
        )
        send("{\"event\":{\"textInput\":{\"promptName\":\"$promptName\",\"contentName\":\"$cn\",\"content\":${escapeJson(text)}}}}")
        send("{\"event\":{\"contentEnd\":{\"promptName\":\"$promptName\",\"contentName\":\"$cn\"}}}")
    }

    val audioContentName = UUID.randomUUID().toString()

    fun audioStart() = send(
        "{\"event\":{\"contentStart\":{\"promptName\":\"$promptName\",\"contentName\":\"$audioContentName\",\"type\":\"AUDIO\"," +
            "\"interactive\":true,\"role\":\"USER\",\"audioInputConfiguration\":{\"mediaType\":\"audio/lpcm\"," +
            "\"sampleRateHertz\":16000,\"sampleSizeBits\":16,\"channelCount\":1,\"audioType\":\"SPEECH\",\"encoding\":\"base64\"}}}}"
    )

    fun end() {
        send("{\"event\":{\"contentEnd\":{\"promptName\":\"$promptName\",\"contentName\":\"$audioContentName\"}}}")
        send("{\"event\":{\"promptEnd\":{\"promptName\":\"$promptName\"}}}")
        send("{\"event\":{\"sessionEnd\":{}}}")
        outbox.close()
    }

    // ---- queries --------------------------------------------------------------------------

    fun blockOf(e: Ev): Block? = synchronized(blocks) { blocks[e.str("contentId") ?: ""] }

    /** Text spoken (ASSISTANT FINAL) in events [fromIdx, end). */
    fun finalAssistantText(fromIdx: Int): String =
        events.drop(fromIdx).filter { it.kind == "textOutput" && blockOf(it)?.let { b -> b.role == "ASSISTANT" && b.stage == "FINAL" } == true }
            .joinToString(" ") { it.str("content") ?: "" }

    fun userFinalText(fromIdx: Int): String =
        events.drop(fromIdx).filter { it.kind == "textOutput" && blockOf(it)?.role == "USER" }
            .joinToString(" ") { it.str("content") ?: "" }

    suspend fun waitFor(from: Int, timeoutMs: Long, pred: (Ev) -> Boolean): Ev? {
        val deadline = nowMs() + timeoutMs
        var i = from
        while (nowMs() < deadline) {
            while (i < events.size) { val e = events[i++]; if (pred(e)) return e }
            if (streamEnded) { while (i < events.size) { val e = events[i++]; if (pred(e)) return e }; return null }
            delay(10)
        }
        return null
    }

    /** Waits until the Agent has produced something after [fromIdx] and then been quiet for [quietMs]. */
    suspend fun waitQuiet(fromIdx: Int, quietMs: Long, timeoutMs: Long): Boolean {
        val deadline = nowMs() + timeoutMs
        while (nowMs() < deadline) {
            val anyAgent = events.drop(fromIdx).any { it.kind == "audioOutput" }
            if (anyAgent && nowMs() - lastAudioMs > quietMs) return true
            if (streamEnded) return false
            delay(25)
        }
        return false
    }

    /** Duration of Agent audio (24 kHz PCM16) received in events from [fromIdx]. */
    fun audioDurMs(fromIdx: Int): Long =
        events.drop(fromIdx).filter { it.kind == "audioOutput" }.sumOf { (it.str("content")?.length ?: 0) * 3L / 4 } / 48

    fun close() { rawLog.close() }
}

/** Streams 32 ms frames in real time: silence, or a queued WAV. */
class Feeder(private val s: SonicSession, scope: CoroutineScope) {
    private val frameBytes = 1024 // 512 samples at 16 kHz = 32 ms
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, ByteArray>>()
    @Volatile var onsetSentMs = -1L
    @Volatile var playStartMs = -1L
    @Volatile var playEndMs = -1L
    @Volatile var currentName: String? = null
    @Volatile var paused = false
    private var cur: ByteArray? = null
    private var pos = 0
    private var onsetPos = 0
    private val job = scope.launch(Dispatchers.Default) {
        val start = System.nanoTime()
        var n = 0L
        while (isActive) {
            if (cur == null) {
                queue.poll()?.let { (nm, pcm) ->
                    cur = pcm; pos = 0; currentName = nm; onsetPos = onsetOf(pcm); onsetSentMs = -1; playStartMs = s.nowMs(); playEndMs = -1
                }
            }
            val frame = ByteArray(frameBytes)
            val c = cur
            if (c != null && !paused) {
                val len = minOf(frameBytes, c.size - pos)
                System.arraycopy(c, pos, frame, 0, len)
                if (onsetSentMs < 0 && pos + frameBytes > onsetPos) onsetSentMs = s.nowMs()
                pos += len
                if (pos >= c.size) { cur = null; playEndMs = s.nowMs() }
            }
            s.sendAudio(frame, s.audioContentName)
            n++
            val wait = (start + n * 32_000_000L - System.nanoTime()) / 1_000_000
            if (wait > 0) delay(wait)
        }
    }

    fun play(name: String, pcm: ByteArray) { queue.add(name to pcm) }
    val busy: Boolean get() = cur != null || queue.isNotEmpty()
    fun stop() { job.cancel() }

    private fun onsetOf(pcm: ByteArray): Int {
        var i = 0
        while (i + frameBytes <= pcm.size) {
            var sum = 0.0
            var j = i
            while (j < i + frameBytes) {
                val v = ((pcm[j + 1].toInt() shl 8) or (pcm[j].toInt() and 0xff)).toShort().toInt()
                sum += v.toDouble() * v; j += 2
            }
            if (Math.sqrt(sum / (frameBytes / 2)) > 400) return i
            i += frameBytes
        }
        return 0
    }
}

fun loadWav(name: String): ByteArray {
    val f = listOf("spike/src/main/assets/s7/wav/$name.wav", "../spike/src/main/assets/s7/wav/$name.wav").map(::File).first { it.exists() }
    return f.readBytes().drop(44).toByteArray()
}
