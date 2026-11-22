package com.callanswer.app.nlg

import com.callanswer.core.stalling.StallPrompt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional cloud LLM for stalling replies (Requirements 5.4, 5.7, 8.3, 10.7).
 *
 * Off by default ([enabled] returns false until the User turns it on). HTTPS only. The API key comes from
 * the Keystore-backed secret store via [apiKey]. The request asks the provider not to retain data
 * (`store: false`). No request, response, key or error text is logged, and failures are reported as null
 * or an exception without payload details. The prompt is built by `PromptBuilder`, so it holds only
 * sanitized Caller text, the language and recent turns.
 *
 * Speaks an OpenAI-compatible `chat/completions` JSON protocol.
 */
class CloudLlmClient(
    private val enabled: () -> Boolean,
    private val endpoint: () -> String?,
    private val apiKey: () -> String?,
    private val model: () -> String = { DEFAULT_MODEL },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMs: Int = 1500,
) {
    /** True when the client is enabled, has an HTTPS endpoint and a key. */
    fun isConfigured(): Boolean = enabled() && httpsUrlOrNull() != null && !apiKey().isNullOrBlank()

    /** The reply text, or null when disabled, unconfigured or the response has no text. Throws on I/O failure. */
    suspend fun complete(prompt: StallPrompt): String? {
        if (!enabled()) return null
        val url = httpsUrlOrNull() ?: return null
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return null
        val body = buildBody(prompt)
        return withContext(io) {
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode !in 200..299) throw java.io.IOException("cloud status")
                parseReply(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun httpsUrlOrNull(): URL? {
        val raw = endpoint()?.trim().orEmpty()
        if (!raw.startsWith("https://", ignoreCase = true)) return null
        return runCatching { URL(raw) }.getOrNull()
    }

    private fun buildBody(prompt: StallPrompt): String {
        val messages: JsonArray = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", prompt.system) })
            prompt.messages.forEach {
                add(buildJsonObject {
                    put("role", if (it.role == com.callanswer.core.stalling.PromptMessage.Role.USER) "user" else "assistant")
                    put("content", it.content)
                })
            }
        }
        return buildJsonObject {
            put("model", model())
            put("messages", messages)
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0.8)
            put("store", false) // no-retention request option (10.7)
        }.toString()
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-4o-mini"
        private const val MAX_TOKENS = 80
        private val json = Json { ignoreUnknownKeys = true }

        internal fun parseReply(text: String): String? = runCatching {
            val root: JsonObject = json.parseToJsonElement(text).jsonObject
            root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]
                ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
