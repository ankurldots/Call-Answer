package com.callanswer.app.api

import com.callanswer.core.model.CallLogEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** `GET /settings` and the 200 of `PUT /settings`. [settings] stays a JSON object so edits can be merged field by field. */
@Serializable
data class SettingsDocument(val version: Long, val settings: JsonObject)

/** `GET /me`. */
@Serializable
data class Profile(
    val userId: String,
    val status: String = "ACTIVE",
    val virtualNumber: String? = null,
    val consentAccepted: Boolean = false,
    val settingsVersion: Long = 0,
)

/** One page of `GET /calls`, newest first. */
@Serializable
data class CallPage(val calls: List<CallLogEntry>, val nextCursor: String? = null)

@Serializable
internal data class ConsentBody(val version: String)

@Serializable
internal data class PutSettingsBody(val expectedVersion: Long, val settings: JsonObject)

@Serializable
internal data class ConflictBody(val currentVersion: Long, val settings: JsonObject)

@Serializable
internal data class InvalidBody(val field: String = "", val message: String = "")

/** Result of `PUT /settings`. */
sealed interface PutSettingsResult {
    data class Saved(val document: SettingsDocument) : PutSettingsResult
    /** 409: [current] is the stored document. */
    data class Conflict(val current: SettingsDocument) : PutSettingsResult
    /** 400: the server rejected [field]. */
    data class Invalid(val field: String, val message: String) : PutSettingsResult
}

/** Why a call to the companion API failed. Never carries the token or a response body. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No connection, timeout, or an unreadable answer. The caller keeps its cache and pending edit. */
    class Network(cause: Throwable? = null) : ApiException("network", cause)
    /** 401 after one token refresh, or the Cognito session is gone. */
    class Unauthorized : ApiException("unauthorized")
    /** 403: the User is deactivated or not in the required group. */
    class Forbidden : ApiException("forbidden")
    class NotFound : ApiException("not found")
    /** 429: wait [retryAfterSec] (when the server said) before the next call. */
    class RateLimited(val retryAfterSec: Long?) : ApiException("rate limited")
    /** Another 4xx. */
    class Client(val status: Int, val field: String? = null) : ApiException("client error $status")
    class Server(val status: Int) : ApiException("server error $status")
}

/** `GET /calls/{id}/recording`: a temporary https URL that expires within 15 minutes. */
@Serializable
data class RecordingUrl(val url: String, val expiresInSeconds: Long)

/** `GET /usage/month`: the Cost_Estimate total of the current month in paise and the limit in INR (null when unset). */
@Serializable
data class MonthUsage(val month: String, val totalPaise: Long, val monthlyLimitInr: Long? = null)
