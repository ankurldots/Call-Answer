package com.callanswer.app.api

import com.callanswer.app.auth.AuthException
import com.callanswer.core.model.CoreJson
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/** One event from `/live`: `call_started`, `transcript`, `intent`, `language`, `state`, `takeover_status`, or `call_ended`. */
data class LiveEvent(val type: String, val data: JsonObject)

/**
 * WebSocket client of `/live`. The Cognito ID token goes in the first message, never in the URL, so it does not reach
 * access logs. The flow ends with [ApiException] when the connection fails (the caller decides when to reconnect) and
 * closes the socket when the collector stops.
 */
class LiveSocket(
    private val config: ApiConfig,
    private val tokens: TokenSource,
    http: OkHttpClient = CompanionApi.defaultClient(),
) {
    // Ping every 20 seconds (the server counts the connection once for the rate limit).
    private val client = http.newBuilder().pingInterval(PING_SECONDS, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    fun events(): Flow<LiveEvent> = callbackFlow {
        val token = try {
            tokens.idToken(false)
        } catch (e: AuthException) {
            throw ApiException.Unauthorized()
        }
        val socket = client.newWebSocket(Request.Builder().url(config.liveUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildJsonObject { put("type", "auth"); put("token", token) }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { CoreJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return
                trySend(LiveEvent(type, obj))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSE, null)
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(if (response?.code == 401) ApiException.Unauthorized() else ApiException.Network(t))
            }
        })
        awaitClose { socket.close(NORMAL_CLOSE, null) }
    }

    private companion object {
        const val PING_SECONDS = 20L
        const val NORMAL_CLOSE = 1000
    }
}
