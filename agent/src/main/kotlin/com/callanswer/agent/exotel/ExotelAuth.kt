package com.callanswer.agent.exotel

import com.callanswer.agent.Clock
import com.callanswer.agent.SystemClock
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Reads a secret value by name (Secrets Manager in production, injected in tests). Never logged. */
fun interface SecretSource {
    fun get(name: String): String?
}

/**
 * Authenticates Exotel requests (HTTP Basic on the WebSocket handshake and the Exotel webhooks)
 * and issues and verifies the call token of the Voice_Stream URL.
 *
 * The Basic credential is the secret [webhookSecretName] in the form `user:password`; the HMAC key is
 * the secret [callTokenSecretName]. Both are read on every use so rotation takes effect without restart.
 */
class ExotelAuth(
    private val secrets: SecretSource,
    private val webhookSecretName: String,
    private val callTokenSecretName: String,
    private val clock: Clock = SystemClock,
    private val tokenTtlMillis: Long = TOKEN_TTL_MILLIS,
) {
    /** True when [authorizationHeader] is a Basic header equal to the stored credential. */
    fun checkBasic(authorizationHeader: String?): Boolean {
        val header = authorizationHeader?.trim() ?: return false
        if (!header.regionMatches(0, BASIC_PREFIX, 0, BASIC_PREFIX.length, ignoreCase = true)) return false
        val presented = try {
            Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length).trim())
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = secrets.get(webhookSecretName)?.takeIf { it.isNotEmpty() } ?: return false
        return constantTimeEquals(presented, expected.toByteArray(Charsets.UTF_8))
    }

    /** Creates a token bound to [callSid] and [userId] that expires 60 seconds from now. */
    fun createCallToken(callSid: String, userId: String): String {
        val expiry = clock.nowMillis() + tokenTtlMillis
        val body = "${b64(callSid)}.${b64(userId)}.$expiry"
        return "$body.${b64(mac(body))}"
    }

    /** Returns the User_Id of a valid, unexpired token for [callSid], or null. */
    fun verifyCallToken(token: String, callSid: String): String? {
        val parts = token.split('.')
        if (parts.size != 4) return null
        val body = "${parts[0]}.${parts[1]}.${parts[2]}"
        val presentedMac = try {
            Base64.getUrlDecoder().decode(parts[3])
        } catch (_: IllegalArgumentException) {
            return null
        }
        val expectedMac = try {
            mac(body)
        } catch (_: IllegalStateException) {
            return null
        }
        if (!constantTimeEquals(presentedMac, expectedMac)) return null
        val expiry = parts[2].toLongOrNull() ?: return null
        if (clock.nowMillis() >= expiry) return null
        val tokenCallSid: String
        val userId: String
        try {
            tokenCallSid = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
            userId = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (!constantTimeEquals(tokenCallSid.toByteArray(Charsets.UTF_8), callSid.toByteArray(Charsets.UTF_8))) {
            return null
        }
        return userId
    }

    private fun mac(body: String): ByteArray {
        val key = secrets.get(callTokenSecretName)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("call token secret is not available")
        val hmac = Mac.getInstance(HMAC)
        hmac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), HMAC))
        return hmac.doFinal(body.toByteArray(Charsets.UTF_8))
    }

    private fun b64(s: String): String = b64(s.toByteArray(Charsets.UTF_8))

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    companion object {
        const val TOKEN_TTL_MILLIS = 60_000L
        private const val BASIC_PREFIX = "Basic "
        private const val HMAC = "HmacSHA256"
    }
}
