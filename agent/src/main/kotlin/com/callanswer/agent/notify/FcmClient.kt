package com.callanswer.agent.notify

import com.callanswer.agent.Clock
import com.callanswer.agent.SystemClock
import com.callanswer.agent.exotel.SecretSource
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class HttpReply(val status: Int, val body: String)

/** The HTTP engine of [FcmClient]. Tests inject a fake, so no test calls Google. */
fun interface HttpPoster {
    suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply
}

/** [HttpPoster] on the JDK client, with a 10-second request timeout. */
class JdkHttpPoster(
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) : HttpPoster {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpReply {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> b.header(k, v) }
        val r = client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofString()).await()
        return HttpReply(r.statusCode(), r.body())
    }
}

/** Result of one send to one device token. */
sealed interface FcmResult {
    data object Accepted : FcmResult

    /** The token is no longer valid (HTTP 404 `UNREGISTERED`, or 400 `INVALID_ARGUMENT`); remove it. */
    data class TokenInvalid(val code: String) : FcmResult

    /** FCM rejected the message or could not be reached; [code] is a short safe code. The token stays. */
    data class Failed(val code: String) : FcmResult
}

/**
 * FCM HTTP v1 client. The service account JSON is read from [secretName] through [secrets] (Secrets Manager in
 * production), a short-lived OAuth access token is minted with a JWT bearer grant (RS256, JDK crypto) and cached
 * until shortly before it expires. Nothing from the secret or the messages is logged.
 */
class FcmClient(
    private val secrets: SecretSource,
    private val secretName: String,
    private val http: HttpPoster = JdkHttpPoster(),
    private val clock: Clock = SystemClock,
    private val projectIdOverride: String? = null,
) {
    private val lock = Mutex()
    private var cached: Pair<String, Long>? = null // access token, expiry epoch ms

    /** Sends [message] to one device [token] as a high-priority data message. Never throws except on cancellation. */
    suspend fun send(token: String, message: PushMessage): FcmResult = try {
        sendOnce(token, message, retryOnAuth = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FcmResult.Failed("FCM_${e.javaClass.simpleName.take(30)}".let { if (Regex("[A-Za-z0-9_]+").matches(it)) it else "FCM_ERROR" })
    }

    private suspend fun sendOnce(token: String, message: PushMessage, retryOnAuth: Boolean): FcmResult {
        val sa = serviceAccount() ?: return FcmResult.Failed("FCM_NO_SECRET")
        val access = accessToken(sa) ?: return FcmResult.Failed("FCM_AUTH_FAILED")
        val body = buildJsonObject {
            put("message", buildJsonObject {
                put("token", token)
                put("data", buildJsonObject { message.toData().forEach { (k, v) -> put(k, v) } })
                put("android", buildJsonObject { put("priority", "HIGH") })
            })
        }.toString()
        val reply = http.post(
            "https://fcm.googleapis.com/v1/projects/${sa.projectId}/messages:send",
            mapOf("Authorization" to "Bearer $access", "Content-Type" to "application/json; charset=utf-8"),
            body,
        )
        return when {
            reply.status in 200..299 -> FcmResult.Accepted
            reply.status == 401 && retryOnAuth -> {
                lock.withLock { cached = null }
                sendOnce(token, message, retryOnAuth = false)
            }
            else -> classify(reply)
        }
    }

    private fun classify(reply: HttpReply): FcmResult {
        val error = runCatching { Json.parseToJsonElement(reply.body).jsonObject["error"]?.jsonObject }.getOrNull()
        val status = error?.get("status")?.jsonPrimitive?.contentOrNull
        val detailCodes = (error?.get("details") as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonObject)?.get("errorCode")?.jsonPrimitive?.contentOrNull
        }
        return if (reply.status == 404 || status == "NOT_FOUND" || "UNREGISTERED" in detailCodes) {
            FcmResult.TokenInvalid("UNREGISTERED")
        } else if (reply.status == 400 && status == "INVALID_ARGUMENT") {
            FcmResult.TokenInvalid("INVALID_ARGUMENT")
        } else {
            FcmResult.Failed("FCM_HTTP_${reply.status}")
        }
    }

    private class ServiceAccount(val email: String, val privateKeyPem: String, val projectId: String, val tokenUri: String)

    private fun serviceAccount(): ServiceAccount? {
        val raw = secrets.get(secretName)?.takeIf { it.isNotBlank() } ?: return null
        val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return ServiceAccount(
            email = s("client_email") ?: return null,
            privateKeyPem = s("private_key") ?: return null,
            projectId = projectIdOverride ?: s("project_id") ?: DEFAULT_PROJECT_ID,
            tokenUri = s("token_uri") ?: DEFAULT_TOKEN_URI,
        )
    }

    private suspend fun accessToken(sa: ServiceAccount): String? = lock.withLock {
        val now = clock.nowMillis()
        cached?.let { (t, exp) -> if (now < exp - EXPIRY_MARGIN_MS) return t }
        val reply = http.post(
            sa.tokenUri,
            mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", Charsets.UTF_8) +
                "&assertion=" + URLEncoder.encode(assertion(sa, now / 1000), Charsets.UTF_8),
        )
        if (reply.status !in 200..299) return null
        val o = runCatching { Json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: return null
        val token = o["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
        val ttl = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        cached = token to now + ttl * 1000
        token
    }

    private fun assertion(sa: ServiceAccount, nowSec: Long): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        fun part(o: JsonElement) = enc.encodeToString(o.toString().toByteArray(Charsets.UTF_8))
        val header = part(buildJsonObject { put("alg", "RS256"); put("typ", "JWT") })
        val claims = part(buildJsonObject {
            put("iss", sa.email)
            put("scope", SCOPE)
            put("aud", sa.tokenUri)
            put("iat", nowSec)
            put("exp", nowSec + 3600)
        })
        val der = Base64.getDecoder().decode(
            sa.privateKeyPem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), ""),
        )
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update("$header.$claims".toByteArray(Charsets.US_ASCII))
            sign()
        }
        return "$header.$claims.${enc.encodeToString(sig)}"
    }

    companion object {
        const val DEFAULT_PROJECT_ID = "call-answer-f43ef"
        const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val EXPIRY_MARGIN_MS = 60_000L
    }
}
