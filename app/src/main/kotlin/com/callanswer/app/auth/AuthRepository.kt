package com.callanswer.app.auth
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val email: String) : AuthState
}
/**
 * Email and password accounts on Cognito, and the temporary AWS credentials used to sign API calls.
 * The password is sent to Cognito over TLS and never stored. Only the refresh token is kept, in the Keystore.
 */
class AuthRepository(
    private val config: AuthConfig,
    private val tokens: TokenStore,
    private val api: CognitoApi = CognitoApi(config),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val nowSec: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val _state = MutableStateFlow<AuthState>(
        tokens.load()?.let { AuthState.SignedIn(it.email) } ?: AuthState.SignedOut,
    )
    val state: StateFlow<AuthState> = _state
    val isConfigured: Boolean get() = config.isConfigured
    private val lock = Mutex()
    private var idToken: String? = null
    private var idTokenExpiresAt = 0L
    private var identityId: String? = null
    private var credentials: AwsCredentials? = null
    suspend fun signUp(email: String, password: String) = onIo {
        api.idp("SignUp", buildJsonObject {
            put("ClientId", config.clientId)
            put("Username", email.trim())
            put("Password", password)
            put("UserAttributes", buildJsonArray {
                add(buildJsonObject { put("Name", "email"); put("Value", email.trim()) })
            })
        })
        Unit
    }
    suspend fun confirmSignUp(email: String, code: String) = onIo {
        api.idp("ConfirmSignUp", buildJsonObject {
            put("ClientId", config.clientId); put("Username", email.trim()); put("ConfirmationCode", code.trim())
        })
        Unit
    }
    suspend fun resendCode(email: String) = onIo {
        api.idp("ResendConfirmationCode", buildJsonObject { put("ClientId", config.clientId); put("Username", email.trim()) })
        Unit
    }
    suspend fun forgotPassword(email: String) = onIo {
        api.idp("ForgotPassword", buildJsonObject { put("ClientId", config.clientId); put("Username", email.trim()) })
        Unit
    }
    suspend fun resetPassword(email: String, code: String, newPassword: String) = onIo {
        api.idp("ConfirmForgotPassword", buildJsonObject {
            put("ClientId", config.clientId); put("Username", email.trim())
            put("ConfirmationCode", code.trim()); put("Password", newPassword)
        })
        Unit
    }
    suspend fun signIn(email: String, password: String) = onIo {
        val result = api.idp("InitiateAuth", buildJsonObject {
            put("AuthFlow", "USER_PASSWORD_AUTH")
            put("ClientId", config.clientId)
            put("AuthParameters", buildJsonObject { put("USERNAME", email.trim()); put("PASSWORD", password) })
        })
        val auth = result["AuthenticationResult"] as? JsonObject ?: throw AuthException(AuthError.UNKNOWN)
        val refresh = auth.str("RefreshToken") ?: throw AuthException(AuthError.UNKNOWN)
        lock.withLock {
            resetCaches()
            cacheIdToken(auth)
            tokens.save(StoredSession(email.trim(), refresh))
        }
        _state.value = AuthState.SignedIn(email.trim())
    }
    /** Revokes the refresh token (best effort) and forgets the session on this device. */
    suspend fun signOut() = onIo {
        val session = tokens.load()
        lock.withLock {
            tokens.clear()
            resetCaches()
        }
        _state.value = AuthState.SignedOut
        if (session != null && config.isConfigured) {
            runCatching {
                api.idp("RevokeToken", buildJsonObject { put("ClientId", config.clientId); put("Token", session.refreshToken) })
            }
        }
    }
    /** Credentials for signing API calls. Throws [AuthException] (NOT_SIGNED_IN when the session is gone). */
    suspend fun credentials(): AwsCredentials = onIo {
        if (!config.isConfigured) throw AuthException(AuthError.NOT_CONFIGURED)
        lock.withLock {
            credentials?.takeIf { it.expiresAtEpochSec - nowSec() > REFRESH_MARGIN_SEC }?.let { return@withLock it }
            val token = freshIdToken()
            val logins = buildJsonObject { put(config.loginsKey, token) }
            val id = identityId ?: api.identity("GetId", buildJsonObject {
                put("IdentityPoolId", config.identityPoolId); put("Logins", logins)
            }).str("IdentityId")?.also { identityId = it } ?: throw AuthException(AuthError.UNKNOWN)
            val c = api.identity("GetCredentialsForIdentity", buildJsonObject {
                put("IdentityId", id); put("Logins", logins)
            })["Credentials"] as? JsonObject ?: throw AuthException(AuthError.UNKNOWN)
            AwsCredentials(
                accessKeyId = c.str("AccessKeyId") ?: throw AuthException(AuthError.UNKNOWN),
                secretKey = c.str("SecretKey") ?: throw AuthException(AuthError.UNKNOWN),
                sessionToken = c.str("SessionToken") ?: throw AuthException(AuthError.UNKNOWN),
                expiresAtEpochSec = c["Expiration"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: (nowSec() + 900),
            ).also { credentials = it }
        }
    }
    /** Must run under [lock]. */
    private fun freshIdToken(): String {
        idToken?.takeIf { idTokenExpiresAt - nowSec() > REFRESH_MARGIN_SEC }?.let { return it }
        val session = tokens.load() ?: throw AuthException(AuthError.NOT_SIGNED_IN)
        val result = try {
            api.idp("InitiateAuth", buildJsonObject {
                put("AuthFlow", "REFRESH_TOKEN_AUTH")
                put("ClientId", config.clientId)
                put("AuthParameters", buildJsonObject { put("REFRESH_TOKEN", session.refreshToken) })
            })
        } catch (e: AuthException) {
            if (e.error == AuthError.WRONG_CREDENTIALS) { // refresh token revoked or expired
                tokens.clear(); resetCaches(); _state.value = AuthState.SignedOut
                throw AuthException(AuthError.NOT_SIGNED_IN)
            }
            throw e
        }
        val auth = result["AuthenticationResult"] as? JsonObject ?: throw AuthException(AuthError.UNKNOWN)
        return cacheIdToken(auth)
    }
    private fun cacheIdToken(auth: JsonObject): String {
        val t = auth.str("IdToken") ?: throw AuthException(AuthError.UNKNOWN)
        idToken = t
        idTokenExpiresAt = nowSec() + (auth["ExpiresIn"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 3600)
        return t
    }
    private fun resetCaches() { idToken = null; idTokenExpiresAt = 0; identityId = null; credentials = null }
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(io) { block() }
    private companion object { const val REFRESH_MARGIN_SEC = 60L }
}
