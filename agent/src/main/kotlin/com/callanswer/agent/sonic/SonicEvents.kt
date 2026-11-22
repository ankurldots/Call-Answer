package com.callanswer.agent.sonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A text message of the conversation history (role is `USER` or `ASSISTANT`). */
data class HistoryMessage(val role: String, val text: String)

/** Names of the content blocks of a session that the server opened. */
data class SessionIds(val promptName: String, val audioContentName: String)

/** Builders for every Nova 2 Sonic input event. Each function returns one JSON event as a string. */
object SonicEvents {
    const val SERVER_MARKER = "[[SERVER]]"
    const val SAMPLE_RATE_IN = 16000
    const val SAMPLE_RATE_OUT = 24000

    private val json = Json

    private fun event(name: String, body: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), buildJsonObject { put("event", buildJsonObject { put(name, body) }) })

    fun sessionStart(
        maxTokens: Int = 2048,
        topP: Double = 0.9,
        temperature: Double = 0.7,
        endpointingSensitivity: String = "MEDIUM",
    ): String = event("sessionStart", buildJsonObject {
        putJsonObject("inferenceConfiguration") {
            put("maxTokens", maxTokens)
            put("topP", topP)
            put("temperature", temperature)
        }
        putJsonObject("turnDetectionConfiguration") { put("endpointingSensitivity", endpointingSensitivity) }
    })

    fun promptStart(promptName: String, voiceId: String, tools: List<ToolSpec> = ToolSpecs.all): String =
        event("promptStart", buildJsonObject {
            put("promptName", promptName)
            putJsonObject("textOutputConfiguration") { put("mediaType", "text/plain") }
            putJsonObject("audioOutputConfiguration") {
                put("mediaType", "audio/lpcm")
                put("sampleRateHertz", SAMPLE_RATE_OUT)
                put("sampleSizeBits", 16)
                put("channelCount", 1)
                put("voiceId", voiceId)
                put("encoding", "base64")
                put("audioType", "SPEECH")
            }
            putJsonObject("toolUseOutputConfiguration") { put("mediaType", "application/json") }
            putJsonObject("toolConfiguration") {
                putJsonObject("toolChoice") { putJsonObject("auto") {} }
                putJsonArray("tools") {
                    tools.forEach { t ->
                        add(buildJsonObject {
                            putJsonObject("toolSpec") {
                                put("name", t.name)
                                put("description", t.description)
                                putJsonObject("inputSchema") { put("json", t.schema) }
                            }
                        })
                    }
                }
            }
        })

    /** `contentStart` of a text block. [role] is `SYSTEM`, `USER`, or `ASSISTANT`. */
    fun textContentStart(promptName: String, contentName: String, role: String, interactive: Boolean): String =
        event("contentStart", buildJsonObject {
            put("promptName", promptName)
            put("contentName", contentName)
            put("type", "TEXT")
            put("interactive", interactive)
            put("role", role)
            putJsonObject("textInputConfiguration") { put("mediaType", "text/plain") }
        })

    fun textInput(promptName: String, contentName: String, text: String): String =
        event("textInput", buildJsonObject {
            put("promptName", promptName)
            put("contentName", contentName)
            put("content", text)
        })

    fun contentEnd(promptName: String, contentName: String): String =
        event("contentEnd", buildJsonObject {
            put("promptName", promptName)
            put("contentName", contentName)
        })

    /** The audio block (`USER`, interactive, 16 kHz PCM16 mono). */
    fun audioContentStart(promptName: String, contentName: String): String =
        event("contentStart", buildJsonObject {
            put("promptName", promptName)
            put("contentName", contentName)
            put("type", "AUDIO")
            put("interactive", true)
            put("role", "USER")
            putJsonObject("audioInputConfiguration") {
                put("mediaType", "audio/lpcm")
                put("sampleRateHertz", SAMPLE_RATE_IN)
                put("sampleSizeBits", 16)
                put("channelCount", 1)
                put("audioType", "SPEECH")
                put("encoding", "base64")
            }
        })

    fun audioInput(promptName: String, contentName: String, base64Audio: String): String =
        event("audioInput", buildJsonObject {
            put("promptName", promptName)
            put("contentName", contentName)
            put("content", base64Audio)
        })

    /** `contentStart` of the result block that answers the tool use [toolUseId]. */
    fun toolContentStart(promptName: String, contentName: String, toolUseId: String): String =
        event("contentStart", buildJsonObject {
            put("promptName", promptName)
            put("contentName", contentName)
            put("interactive", false)
            put("type", "TOOL")
            put("role", "TOOL")
            putJsonObject("toolResultInputConfiguration") {
                put("toolUseId", toolUseId)
                put("type", "TEXT")
                putJsonObject("textInputConfiguration") { put("mediaType", "text/plain") }
            }
        })

    /** [resultJson] is the stringified JSON object that the tool returned. */
    fun toolResult(promptName: String, contentName: String, resultJson: String): String =
        event("toolResult", buildJsonObject {
            put("promptName", promptName)
            put("contentName", contentName)
            put("content", resultJson)
        })

    fun promptEnd(promptName: String): String = event("promptEnd", buildJsonObject { put("promptName", promptName) })

    fun sessionEnd(): String = event("sessionEnd", buildJsonObject {})

    /** `contentStart`, `textInput`, `contentEnd` of one text block. */
    fun textBlock(promptName: String, contentName: String, role: String, interactive: Boolean, text: String): List<String> =
        listOf(
            textContentStart(promptName, contentName, role, interactive),
            textInput(promptName, contentName, text),
            contentEnd(promptName, contentName),
        )

    /**
     * Events from `sessionStart` up to and including the audio `contentStart`, in the documented
     * order: sessionStart, promptStart, system prompt, history (replacement session only), audio start.
     * [newName] supplies unique content names.
     */
    fun startSequence(
        ids: SessionIds,
        voiceId: String,
        systemPrompt: String,
        history: List<HistoryMessage> = emptyList(),
        tools: List<ToolSpec> = ToolSpecs.all,
        endpointingSensitivity: String = "MEDIUM",
        newName: () -> String = { java.util.UUID.randomUUID().toString() },
    ): List<String> = buildList {
        add(sessionStart(endpointingSensitivity = endpointingSensitivity))
        add(promptStart(ids.promptName, voiceId, tools))
        addAll(textBlock(ids.promptName, newName(), "SYSTEM", false, systemPrompt))
        history.forEach { addAll(textBlock(ids.promptName, newName(), it.role, false, it.text)) }
        add(audioContentStart(ids.promptName, ids.audioContentName))
    }

    /** Text that the server wants Sonic to say, as marked cross-modal text (role `USER`, interactive). */
    fun serverInstruction(ids: SessionIds, contentName: String, instruction: String): List<String> =
        textBlock(ids.promptName, contentName, "USER", true, instruction)

    /** `[[SERVER]] Say exactly: "<sentence>"` (spike C-5 form; verified 10 of 10). */
    fun sayExactly(sentence: String): String = "$SERVER_MARKER Say exactly: \"${sentence.replace('"', '\'')}\""

    /** `[[SERVER]] Greet the caller now. Say exactly: "<greeting>"`. */
    fun greetingTrigger(greeting: String): String =
        "$SERVER_MARKER Greet the caller now. Say exactly: \"${greeting.replace('"', '\'')}\""

    fun endSequence(ids: SessionIds): List<String> = listOf(
        contentEnd(ids.promptName, ids.audioContentName),
        promptEnd(ids.promptName),
        sessionEnd(),
    )

    // ---------------------------------------------------------------- output events

    /** Parses one output event; malformed or unknown events become [SonicOutput.Unknown]. */
    fun parse(text: String): SonicOutput {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return SonicOutput.Unknown("malformed")
        }
        val ev = (root["event"] as? JsonObject) ?: return SonicOutput.Unknown("no-event")
        val name = ev.keys.firstOrNull() ?: return SonicOutput.Unknown("empty")
        val b = (ev[name] as? JsonObject) ?: return SonicOutput.Unknown(name)
        return try {
            parseBody(name, b)
        } catch (e: Exception) {
            SonicOutput.Unknown(name)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun parseBody(name: String, b: JsonObject): SonicOutput = when (name) {
        "completionStart" -> SonicOutput.CompletionStart(b.str("completionId").orEmpty())
        "completionEnd" -> SonicOutput.CompletionEnd(b.str("completionId").orEmpty(), b.str("stopReason"))
        "usageEvent" -> {
            val d = b["details"]?.jsonObject?.get("delta")?.jsonObject
            fun tok(side: String, kind: String) =
                d?.get(side)?.jsonObject?.get(kind)?.jsonPrimitive?.intOrNull ?: 0
            SonicOutput.Usage(tok("input", "speechTokens"), tok("input", "textTokens"), tok("output", "speechTokens"), tok("output", "textTokens"))
        }
        "contentStart" -> {
            val stage = b.str("additionalModelFields")?.let {
                try { json.parseToJsonElement(it).jsonObject.str("generationStage") } catch (e: Exception) { null }
            }
            SonicOutput.ContentStart(
                contentId = b.str("contentId").orEmpty(),
                type = b.str("type").orEmpty(),
                role = b.str("role").orEmpty(),
                generationStage = stage,
            )
        }
        "textOutput" -> SonicOutput.TextOutput(b.str("contentId").orEmpty(), b.str("role").orEmpty(), b.str("content").orEmpty())
        "audioOutput" -> SonicOutput.AudioOutput(b.str("contentId").orEmpty(), b.str("content").orEmpty())
        "toolUse" -> SonicOutput.ToolUse(
            contentId = b.str("contentId").orEmpty(),
            toolName = b.str("toolName").orEmpty(),
            toolUseId = b.str("toolUseId").orEmpty(),
            contentJson = b.str("content").orEmpty(),
        )
        "contentEnd" -> SonicOutput.ContentEnd(b.str("contentId").orEmpty(), b.str("type").orEmpty(), b.str("stopReason"))
        "userSpeechStart" -> SonicOutput.UserSpeechStart(b["inputAudioOffsetMs"]?.jsonPrimitive?.intOrNull)
        "userSpeechEnd" -> SonicOutput.UserSpeechEnd(b["inputAudioOffsetMs"]?.jsonPrimitive?.intOrNull)
        else -> SonicOutput.Unknown(name)
    }
}

/** Parsed Sonic output events. */
sealed interface SonicOutput {
    data class CompletionStart(val completionId: String) : SonicOutput
    data class CompletionEnd(val completionId: String, val stopReason: String?) : SonicOutput
    data class Usage(val inputSpeech: Int, val inputText: Int, val outputSpeech: Int, val outputText: Int) : SonicOutput

    /** [generationStage] is `SPECULATIVE` or `FINAL` for text blocks, null otherwise. */
    data class ContentStart(val contentId: String, val type: String, val role: String, val generationStage: String?) : SonicOutput
    data class TextOutput(val contentId: String, val role: String, val content: String) : SonicOutput
    data class AudioOutput(val contentId: String, val base64: String) : SonicOutput
    data class ToolUse(val contentId: String, val toolName: String, val toolUseId: String, val contentJson: String) : SonicOutput

    /** `INTERRUPTED` arrives as [stopReason] of the assistant TEXT block (spike C-5). */
    data class ContentEnd(val contentId: String, val type: String, val stopReason: String?) : SonicOutput {
        val interrupted: Boolean get() = stopReason == "INTERRUPTED"
    }
    data class UserSpeechStart(val inputAudioOffsetMs: Int?) : SonicOutput
    data class UserSpeechEnd(val inputAudioOffsetMs: Int?) : SonicOutput
    data class Unknown(val name: String) : SonicOutput
}
