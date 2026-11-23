package com.callanswer.app.api

import com.callanswer.app.auth.AuthError
import com.callanswer.app.auth.AuthException
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.CoreJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client of the companion API (`/api/v1`). Every call carries the Cognito ID token as a Bearer token. A 401 refreshes
 * the token once and repeats the call. Neither the token nor a body is logged or placed in an exception.
 */
class CompanionApi(
    private val config: ApiConfig,
    private val tokens: TokenSource,
    private val http: OkHttpClient = defaultClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun me(): Profile = decode(Profile.serializer(), send("GET", "/me"))

    suspend fun getSettings(): SettingsDocument = decode(SettingsDocument.serializer(), send("GET", "/settings"))

    /** `PUT /settings`. 200, 400, and 409 are results; every other failure throws [ApiException]. */
    suspend fun putSettings(expectedVersion: Long, settings: JsonObject): PutSettingsResult {
        val body = CoreJson.encodeToString(PutSettingsBody.serializer(), PutSettingsBody(expectedVersion, settings))
        val r = send("PUT", "/settings", body, accept = setOf(400, 409))
        return when (r.status) {
            409 -> decode(ConflictBody.serializer(), r).let { PutSettingsResult.Conflict(SettingsDocument(it.currentVersion, it.settings)) }
            400 -> decode(InvalidBody.serializer(), r).let { PutSettingsResult.Invalid(it.field, it.message) }
            else -> PutSettingsResult.Saved(decode(SettingsDocument.serializer(), r))
        }
    }

    suspend fun listCalls(cursor: String? = null, limit: Int = 50): CallPage {
        val query = buildMap { put("limit", limit.toString()); if (cursor != null) put("cursor", cursor) }
        return decode(CallPage.serializer(), send("GET", "/calls", query = query))
    }

    suspend fun getCall(id: String): CallLogEntry = decode(CallLogEntry.serializer(), send("GET", "/calls/${enc(id)}"))

    suspend fun deleteCall(id: String) { send("DELETE", "/calls/${enc(id)}") }

    /** `GET /calls/{id}/recording`. 404 ([ApiException.NotFound]) when the call has no recording. */
    suspend fun recordingUrl(id: String): RecordingUrl =
        decode(RecordingUrl.serializer(), send("GET", "/calls/${enc(id)}/recording"))

    /** `GET /usage/month`. */
    suspend fun usageMonth(): MonthUsage = decode(MonthUsage.serializer(), send("GET", "/usage/month"))

    /** `GET /recent-callers?minutes=n` (1 to 60): the User's Recent_Call_Records of the last n minutes. */
    suspend fun recentCallers(minutes: Int = 60): List<RecentCaller> =
        decode(RecentCallersResponse.serializer(), send("GET", "/recent-callers", query = mapOf("minutes" to minutes.coerceIn(1, 60).toString()))).callers

    /** `POST /calls/{id}/report-status`: the server stores the spam report status of one call (204). */
    suspend fun reportStatus(id: String, status: com.callanswer.core.model.ReportStatus) {
        send("POST", "/calls/${enc(id)}/report-status", "{\"status\":\"${status.name}\"}")
    }

    /** `POST /calls/{id}/map-sms-result`: the server stores the result of the Map_SMS the device sent (Requirement 10.16). */
    suspend fun mapSmsResult(id: String, status: com.callanswer.app.sms.MapSmsResult) {
        send("POST", "/calls/${enc(id)}/map-sms-result", "{\"status\":\"${status.name}\"}")
    }

    /** `POST /consent`: the server stores the acceptance with the version of the consent text and the time. */
    suspend fun acceptConsent(version: String) {
        send("POST", "/consent", CoreJson.encodeToString(ConsentBody.serializer(), ConsentBody(version)))
    }

    /** `DELETE /consent`: the server turns the Agent off and applies the Fallback_Route. */
    suspend fun withdrawConsent() { send("DELETE", "/consent") }

    internal class Reply(val status: Int, val body: String, val retryAfterSec: Long? = null)

    internal suspend fun send(
        method: String,
        path: String,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        accept: Set<Int> = emptySet(),
    ): Reply {
        val first = attempt(method, path, body, query, forceRefresh = false)
        val reply = if (first.status == 401) attempt(method, path, body, query, forceRefresh = true) else first
        if (reply.status in 200..299 || reply.status in accept) return reply
        throw when (reply.status) {
            401 -> ApiException.Unauthorized()
            403 -> ApiException.Forbidden()
            404 -> ApiException.NotFound()
            429 -> ApiException.RateLimited(reply.retryAfterSec)
            in 400..499 -> ApiException.Client(reply.status)
            else -> ApiException.Server(reply.status)
        }
    }

    private suspend fun attempt(method: String, path: String, body: String?, query: Map<String, String>, forceRefresh: Boolean): Reply {
        val token = try {
            tokens.idToken(forceRefresh)
        } catch (e: AuthException) {
            if (e.error == AuthError.NETWORK) throw ApiException.Network(e) else throw ApiException.Unauthorized()
        }
        val url = (config.baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .method(method, body?.toRequestBody(JSON) ?: if (method == "PUT" || method == "POST") "".toRequestBody() else null)
            .build()
        return withContext(io) {
            try {
                http.newCall(request).execute().use { r: Response ->
                    Reply(r.code, r.body?.string().orEmpty(), r.header("Retry-After")?.toLongOrNull())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }
        }
    }

    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, r: Reply): T =
        try { CoreJson.decodeFromString(s, r.body) } catch (e: kotlinx.serialization.SerializationException) { throw ApiException.Network(e) }

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
