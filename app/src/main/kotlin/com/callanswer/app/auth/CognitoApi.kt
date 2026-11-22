package com.callanswer.app.auth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
/** Why a Cognito call failed, in terms the sign-in screen can show. */
enum class AuthError {
    WRONG_CREDENTIALS, NOT_CONFIRMED, ALREADY_EXISTS, INVALID_PASSWORD, INVALID_EMAIL,
    CODE_INVALID, CODE_EXPIRED, TOO_MANY_REQUESTS, NETWORK, NOT_CONFIGURED, NOT_SIGNED_IN, UNKNOWN,
}
class AuthException(val error: AuthError, cause: Throwable? = null) : Exception(error.name, cause)
/** One HTTPS JSON call; returns the status and body. Replaced in tests. */
fun interface Transport {
    fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String>
}
/** Plain `HttpURLConnection` transport. HTTPS only; never logs payloads. */
object HttpsTransport : Transport {
    override fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        require(url.startsWith("https://")) { "https only" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}
/** Minimal Cognito user pool and identity pool JSON API (no AWS SDK). */
class CognitoApi(private val config: AuthConfig, private val transport: Transport = HttpsTransport) {
    private val json = Json { ignoreUnknownKeys = true }
    /** Calls `cognito-idp` [action] and returns the response object. */
    fun idp(action: String, body: JsonObject): JsonObject =
        call("https://cognito-idp.${config.region}.amazonaws.com/", "AWSCognitoIdentityProviderService.$action", body)
    /** Calls `cognito-identity` [action] and returns the response object. */
    fun identity(action: String, body: JsonObject): JsonObject =
        call("https://cognito-identity.${config.region}.amazonaws.com/", "AWSCognitoIdentityService.$action", body)
    private fun call(url: String, target: String, body: JsonObject): JsonObject {
        val (status, text) = try {
            transport.post(
                url,
                mapOf("Content-Type" to "application/x-amz-json-1.1", "X-Amz-Target" to target),
                body.toString(),
            )
        } catch (e: IOException) {
            throw AuthException(AuthError.NETWORK, e)
        }
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (status !in 200..299) throw AuthException(mapError(obj, status))
        return obj ?: JsonObject(emptyMap())
    }
    companion object {
        internal fun mapError(obj: JsonObject?, status: Int): AuthError {
            val type = (obj?.get("__type") as? JsonPrimitive)?.contentOrNull.orEmpty().substringAfterLast('#')
            return when (type) {
                "NotAuthorizedException", "UserNotFoundException" -> AuthError.WRONG_CREDENTIALS
                "UserNotConfirmedException" -> AuthError.NOT_CONFIRMED
                "UsernameExistsException" -> AuthError.ALREADY_EXISTS
                "InvalidPasswordException" -> AuthError.INVALID_PASSWORD
                "InvalidParameterException" -> AuthError.INVALID_EMAIL
                "CodeMismatchException" -> AuthError.CODE_INVALID
                "ExpiredCodeException" -> AuthError.CODE_EXPIRED
                "TooManyRequestsException", "LimitExceededException", "TooManyFailedAttemptsException" ->
                    AuthError.TOO_MANY_REQUESTS
                else -> if (status >= 500) AuthError.NETWORK else AuthError.UNKNOWN
            }
        }
    }
}
