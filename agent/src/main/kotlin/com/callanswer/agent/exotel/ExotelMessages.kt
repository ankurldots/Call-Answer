package com.callanswer.agent.exotel

import com.callanswer.agent.audio.StreamFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Exotel stream (Voicebot / AgentStream) events.
 *
 * ASSUMPTIONS (spike C-1 not run), based on the documented Exotel format (Voicebot applet page):
 *  - every message is a JSON object with an `event` field, a `sequence_number` and, after `connected`, a `stream_sid`;
 *  - `start` nests `stream_sid`, `call_sid`, `from`, `to`, `media_format` and `custom_parameters` under `start`;
 *  - `custom_parameters` (at most 3 values, 256 characters) may hold strings or numbers; they are
 *    only logged, never used for authentication (the token is in the URL path);
 *  - `media_format.sample_rate` may be a string or a number; `encoding` is "base64";
 *  - `media` nests `chunk`, `timestamp`, `payload` (base64) under `media`;
 *  - `mark` nests `name` under `mark`; `dtmf` nests `digit` (and `duration`) under `dtmf`;
 *  - `stop` nests `call_sid` and `reason` under `stop`; `clear` has only `stream_sid`.
 * Unknown fields are ignored; missing optional fields become null.
 */
sealed interface ExotelEvent {
    data object Connected : ExotelEvent

    data class Start(
        val streamSid: String?,
        val callSid: String?,
        val from: String?,
        val to: String?,
        val format: StreamFormat,
        val sequenceNumber: Long? = null,
        val customParameters: Map<String, String> = emptyMap(),
    ) : ExotelEvent

    /** [payload] is the base64 text exactly as on the wire; decode with PcmCodec/FrameValidator. */
    data class Media(
        val streamSid: String?,
        val payload: String,
        val chunk: Long? = null,
        val timestamp: String? = null,
        val sequenceNumber: Long? = null,
    ) : ExotelEvent

    data class Mark(val streamSid: String?, val name: String, val sequenceNumber: Long? = null) : ExotelEvent

    data class Clear(val streamSid: String?) : ExotelEvent

    data class Dtmf(
        val streamSid: String?,
        val digit: String,
        val duration: String? = null,
        val sequenceNumber: Long? = null,
    ) : ExotelEvent

    data class Stop(
        val streamSid: String?,
        val callSid: String?,
        val reason: String? = null,
        val sequenceNumber: Long? = null,
    ) : ExotelEvent
}

object ExotelMessages {
    private val json = Json { ignoreUnknownKeys = true }

    /** Decodes one wire message; returns null for malformed JSON, unknown events, or missing required fields. */
    fun decode(text: String): ExotelEvent? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val streamSid = root.str("stream_sid")
        val seq = root.long("sequence_number")
        return when (root.str("event")) {
            "connected" -> ExotelEvent.Connected
            "start" -> {
                val s = root.obj("start") ?: return null
                val mf = s.obj("media_format")
                val defaults = StreamFormat()
                val format = StreamFormat(
                    encoding = mf?.str("encoding") ?: defaults.encoding,
                    sampleRate = mf?.int("sample_rate") ?: defaults.sampleRate,
                    frameBytes = mf?.int("frame_bytes"),
                )
                val custom = s.obj("custom_parameters")
                    ?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { k to it } }
                    ?.toMap()
                    ?: emptyMap()
                ExotelEvent.Start(
                    s.str("stream_sid") ?: streamSid, s.str("call_sid"), s.str("from"), s.str("to"), format, seq, custom,
                )
            }
            "media" -> {
                val m = root.obj("media") ?: return null
                val payload = m.str("payload") ?: return null
                ExotelEvent.Media(streamSid, payload, m.long("chunk"), m.str("timestamp"), seq)
            }
            "mark" -> {
                val name = root.obj("mark")?.str("name") ?: return null
                ExotelEvent.Mark(streamSid, name, seq)
            }
            "clear" -> ExotelEvent.Clear(streamSid)
            "dtmf" -> {
                val d = root.obj("dtmf") ?: return null
                ExotelEvent.Dtmf(streamSid, d.str("digit") ?: return null, d.str("duration"), seq)
            }
            "stop" -> {
                val s = root.obj("stop")
                ExotelEvent.Stop(streamSid, s?.str("call_sid"), s?.str("reason"), seq)
            }
            else -> null
        }
    }

    fun encode(event: ExotelEvent): String = json.encodeToString(JsonObject.serializer(), toJson(event))

    private fun toJson(e: ExotelEvent): JsonObject = buildJsonObject {
        fun common(event: String, sid: String?, seq: Long?) {
            put("event", event)
            seq?.let { put("sequence_number", it.toString()) }
            sid?.let { put("stream_sid", it) }
        }
        when (e) {
            ExotelEvent.Connected -> put("event", "connected")
            is ExotelEvent.Start -> {
                common("start", e.streamSid, e.sequenceNumber)
                put("start", buildJsonObject {
                    e.streamSid?.let { put("stream_sid", it) }
                    e.callSid?.let { put("call_sid", it) }
                    e.from?.let { put("from", it) }
                    e.to?.let { put("to", it) }
                    put("media_format", buildJsonObject {
                        put("encoding", e.format.encoding)
                        put("sample_rate", e.format.sampleRate.toString())
                        e.format.frameBytes?.let { put("frame_bytes", it) }
                    })
                    if (e.customParameters.isNotEmpty()) {
                        put("custom_parameters", buildJsonObject { e.customParameters.forEach { (k, v) -> put(k, v) } })
                    }
                })
            }
            is ExotelEvent.Media -> {
                common("media", e.streamSid, e.sequenceNumber)
                put("media", buildJsonObject {
                    e.chunk?.let { put("chunk", it.toString()) }
                    e.timestamp?.let { put("timestamp", it) }
                    put("payload", e.payload)
                })
            }
            is ExotelEvent.Mark -> {
                common("mark", e.streamSid, e.sequenceNumber)
                put("mark", buildJsonObject { put("name", e.name) })
            }
            is ExotelEvent.Clear -> common("clear", e.streamSid, null)
            is ExotelEvent.Dtmf -> {
                common("dtmf", e.streamSid, e.sequenceNumber)
                put("dtmf", buildJsonObject {
                    put("digit", e.digit)
                    e.duration?.let { put("duration", it) }
                })
            }
            is ExotelEvent.Stop -> {
                common("stop", e.streamSid, e.sequenceNumber)
                put("stop", buildJsonObject {
                    e.callSid?.let { put("call_sid", it) }
                    e.reason?.let { put("reason", it) }
                })
            }
        }
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = prim(key)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    private fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonElement)?.let { it as? JsonObject }
}
