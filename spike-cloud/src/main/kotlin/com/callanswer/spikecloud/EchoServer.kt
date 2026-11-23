package com.callanswer.spikecloud

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.Base64
import java.util.UUID

/** Settings of the C-1 echo server. [chunkBytes] must be a multiple of 320 (16-bit mono, 10 ms at 16 kHz). */
data class EchoConfig(
    val publicHost: String = "localhost:8080",
    val sampleRate: Int = 8000,
    val chunkBytes: Int = 3200,
    val scheme: String = "wss",
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Spike C-1 echo server. `GET /exotel/resolve` returns `{"url":"wss://<host>/stream/<random>?sample-rate=<n>"}`.
 * `/stream/{token}` logs every frame, echoes caller audio back in [EchoConfig.chunkBytes] chunks followed by a
 * `mark`, and sends `clear` on DTMF `#`. The Authorization header is logged only as present/absent and its scheme.
 */
fun Application.echoModule(
    config: EchoConfig,
    log: SpikeLog = SpikeLog("c1"),
    frames: Frames = Frames("exotel"),
) {
    require(config.chunkBytes > 0 && config.chunkBytes % 320 == 0) { "ECHO_CHUNK_BYTES must be a multiple of 320" }
    install(WebSockets)
    routing {
        get("/exotel/resolve") {
            val rate = call.request.queryParameters["sample-rate"]?.toIntOrNull() ?: config.sampleRate
            val token = UUID.randomUUID().toString().replace("-", "")
            val url = "${config.scheme}://${config.publicHost}/stream/$token?sample-rate=$rate"
            log.log("resolve", mapOf("query" to call.request.queryParameters.entries().joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" },
                "auth" to authSummary(call.request.header("Authorization")), "sampleRate" to rate))
            call.respondText(buildJsonObject { put("url", url) }.toString(), ContentType.Application.Json)
        }
        webSocket("/stream/{token}") {
            val token = call.parameters["token"].orEmpty()
            log.log("ws_open", mapOf(
                "tokenLength" to token.length,
                "query" to call.request.queryParameters.entries().joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" },
                "auth" to authSummary(call.request.header("Authorization")),
            ))
            var streamSid: String? = null
            var marks = 0
            for (frame in incoming) {
                if (frame !is Frame.Text) {
                    log.log("frame_non_text", mapOf("type" to frame.frameType.name, "bytes" to frame.data.size))
                    continue
                }
                val text = frame.readText()
                frames.record("in", text)
                val root: JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val event = root?.get("event")?.jsonPrimitive?.contentOrNull
                log.log("frame_in", mapOf("event" to event, "bytes" to text.length, "body" to if (event == "media") "<media>" else text))
                if (root == null) continue
                if (event == "start") {
                    streamSid = root["stream_sid"]?.jsonPrimitive?.contentOrNull
                        ?: root["start"]?.jsonObject?.get("stream_sid")?.jsonPrimitive?.contentOrNull
                }
                when (event) {
                    "media" -> {
                        val payload = root["media"]?.jsonObject?.get("payload")?.jsonPrimitive?.contentOrNull ?: continue
                        val pcm = runCatching { Base64.getDecoder().decode(payload) }.getOrNull() ?: continue
                        log.log("media_in", mapOf("bytes" to pcm.size))
                        var offset = 0
                        while (offset < pcm.size) {
                            val end = minOf(offset + config.chunkBytes, pcm.size)
                            val chunk = pcm.copyOfRange(offset, end).copyOf(config.chunkBytes)
                            send(out(frames, log, mediaJson(streamSid, chunk)))
                            log.log("media_out", mapOf("bytes" to chunk.size))
                            offset = end
                        }
                        val name = "echo-${++marks}"
                        send(out(frames, log, markJson(streamSid, name)))
                        log.log("mark_out", mapOf("name" to name))
                    }
                    "dtmf" -> {
                        val digit = root["dtmf"]?.jsonObject?.get("digit")?.jsonPrimitive?.contentOrNull
                        if (digit == "#") {
                            send(out(frames, log, clearJson(streamSid)))
                            log.log("clear_out")
                        }
                    }
                }
            }
            log.log("ws_close")
        }
    }
}

private fun out(frames: Frames, log: SpikeLog, text: String): Frame.Text {
    frames.record("out", text)
    return Frame.Text(text)
}

private fun authSummary(header: String?): String =
    if (header.isNullOrBlank()) "absent" else "present:" + header.substringBefore(' ').take(16)

private fun mediaJson(sid: String?, pcm: ByteArray) = buildJsonObject {
    put("event", "media")
    sid?.let { put("stream_sid", it) }
    put("media", buildJsonObject { put("payload", Base64.getEncoder().encodeToString(pcm)) })
}.toString()

private fun markJson(sid: String?, name: String) = buildJsonObject {
    put("event", "mark")
    sid?.let { put("stream_sid", it) }
    put("mark", buildJsonObject { put("name", name) })
}.toString()

private fun clearJson(sid: String?) = buildJsonObject {
    put("event", "clear")
    sid?.let { put("stream_sid", it) }
}.toString()

/** ./gradlew :spike-cloud:c1 -Pport=8080 ; env ECHO_PUBLIC_HOST, ECHO_SAMPLE_RATE, ECHO_CHUNK_BYTES, ECHO_SCHEME. */
fun main() {
    val port = System.getProperty("port")?.toIntOrNull() ?: 8080
    val env = System.getenv()
    val config = EchoConfig(
        publicHost = env["ECHO_PUBLIC_HOST"] ?: "localhost:$port",
        sampleRate = env["ECHO_SAMPLE_RATE"]?.toIntOrNull() ?: 8000,
        chunkBytes = env["ECHO_CHUNK_BYTES"]?.toIntOrNull() ?: 3200,
        scheme = env["ECHO_SCHEME"] ?: "wss",
    )
    File("spike-cloud/results").mkdirs()
    embeddedServer(Netty, port = port) { echoModule(config) }.start(wait = true)
}
