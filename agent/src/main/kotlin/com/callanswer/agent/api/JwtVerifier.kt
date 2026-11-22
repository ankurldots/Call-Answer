package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.SystemClock
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The identity taken from a verified token. [sub] is the only source of the User_Id. */
data class VerifiedToken(val sub: String, val groups: Set<String>)

class JwtException(message: String) : Exception(message)

/**
 * Verifies Cognito tokens (RS256) against the pool's JWKS. The JWKS is fetched through [jwksSource]
 * (a URL download by default, an injected document in tests) and cached for [cacheMillis].
 * Checks: signature, `iss`, audience (`aud` of an ID token or `client_id` of an access token),
 * `exp`, and `token_use`.
 */
class JwtVerifier(
    private val issuer: String,
    private val clientId: String,
    private val jwksSource: () -> String,
    private val clock: Clock = SystemClock,
    private val cacheMillis: Long = CACHE_MILLIS,
) {
    private var keys: Map<String, PublicKey> = emptyMap()
    private var loadedAt: Long = Long.MIN_VALUE

    fun verify(token: String): VerifiedToken {
        val parts = token.split('.')
        if (parts.size != 3) throw JwtException("malformed token")
        val header = parseObject(parts[0])
        val claims = parseObject(parts[1])
        if (header["alg"]?.jsonPrimitive?.contentOrNull != "RS256") throw JwtException("unsupported alg")
        val kid = header["kid"]?.jsonPrimitive?.contentOrNull ?: throw JwtException("missing kid")
        val key = keyFor(kid) ?: throw JwtException("unknown kid")
        val signature = try {
            Base64.getUrlDecoder().decode(parts[2])
        } catch (e: IllegalArgumentException) {
            throw JwtException("bad signature encoding")
        }
        val ok = try {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(signature)
            }
        } catch (e: java.security.GeneralSecurityException) {
            false
        }
        if (!ok) throw JwtException("bad signature")

        if (claims.str("iss") != issuer) throw JwtException("wrong issuer")
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull ?: throw JwtException("missing exp")
        if (exp * 1000 <= clock.nowMillis()) throw JwtException("expired")
        when (claims.str("token_use")) {
            "id" -> if (claims.str("aud") != clientId) throw JwtException("wrong audience")
            "access" -> if (claims.str("client_id") != clientId) throw JwtException("wrong client")
            else -> throw JwtException("wrong token_use")
        }
        val sub = claims.str("sub")?.takeIf { it.isNotBlank() } ?: throw JwtException("missing sub")
        val groups = (claims["cognito:groups"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()
        return VerifiedToken(sub, groups)
    }

    @Synchronized
    private fun keyFor(kid: String): PublicKey? {
        val now = clock.nowMillis()
        val stale = loadedAt == Long.MIN_VALUE || now - loadedAt >= cacheMillis
        if (stale) refresh(now)
        else if (kid !in keys && now - loadedAt >= MIN_REFETCH_MILLIS) refresh(now)
        return keys[kid]
    }

    private fun refresh(now: Long) {
        val loaded = try {
            parseJwks(jwksSource())
        } catch (e: Exception) {
            if (keys.isNotEmpty()) return // keep serving the old keys when the download fails
            throw JwtException("JWKS unavailable")
        }
        keys = loaded
        loadedAt = now
    }

    private fun parseJwks(text: String): Map<String, PublicKey> {
        val factory = KeyFactory.getInstance("RSA")
        return Json.parseToJsonElement(text).jsonObject["keys"]!!.jsonArray.mapNotNull { k ->
            val o = k.jsonObject
            if (o["kty"]?.jsonPrimitive?.contentOrNull != "RSA") return@mapNotNull null
            val kid = o["kid"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            fun big(name: String) = BigInteger(1, Base64.getUrlDecoder().decode(o[name]!!.jsonPrimitive.content))
            kid to factory.generatePublic(RSAPublicKeySpec(big("n"), big("e")))
        }.toMap()
    }

    private fun parseObject(part: String): JsonObject = try {
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(part), Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        throw JwtException("malformed token")
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val CACHE_MILLIS = 3_600_000L
        private const val MIN_REFETCH_MILLIS = 60_000L
        const val REGION = "ap-south-1"
        const val USER_POOL_ID = "ap-south-1_TKxhHQ1Bq"
        const val POOL_ISSUER = "https://cognito-idp.$REGION.amazonaws.com/$USER_POOL_ID"

        /** Downloads the JWKS of [issuer]. */
        fun downloadJwks(issuer: String = POOL_ISSUER): () -> String {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
            return {
                val req = HttpRequest.newBuilder(URI.create("$issuer/.well-known/jwks.json"))
                    .timeout(Duration.ofSeconds(5)).GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                check(resp.statusCode() == 200) { "JWKS status ${resp.statusCode()}" }
                resp.body()
            }
        }
    }
}
