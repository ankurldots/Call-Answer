package com.callanswer.app.auth
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
/** Temporary AWS credentials from Cognito. [expiresAtEpochSec] is when [sessionToken] stops working. */
class AwsCredentials(
    val accessKeyId: String,
    val secretKey: String,
    val sessionToken: String,
    val expiresAtEpochSec: Long,
)
/**
 * AWS Signature Version 4 for a request with a body and no query string. The path must already be
 * URI-encoded; the single-segment paths this app uses need no encoding.
 */
object SigV4 {
    private val amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
    /**
     * Returns the headers to add to the request: `x-amz-date`, `x-amz-security-token` (when the credentials
     * have one) and `authorization`. The `host` header is signed but left to the HTTP client to send.
     * [headers] are extra headers to sign (lower-case names, e.g. `content-type`).
     */
    fun sign(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        credentials: AwsCredentials,
        region: String,
        service: String,
        now: Instant,
    ): Map<String, String> {
        val uri = URI(url)
        require(uri.rawQuery == null) { "query strings are not supported" }
        val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
        val path = uri.rawPath.ifEmpty { "/" }
        val date = amzDate.format(now)
        val day = dateStamp.format(now)
        val toSign = sortedMapOf<String, String>()
        headers.forEach { (k, v) -> toSign[k.lowercase()] = v.trim() }
        toSign["host"] = host
        toSign["x-amz-date"] = date
        if (credentials.sessionToken.isNotEmpty()) toSign["x-amz-security-token"] = credentials.sessionToken
        val canonicalHeaders = toSign.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedHeaders = toSign.keys.joinToString(";")
        val canonicalRequest = listOf(
            method, path, "", canonicalHeaders, signedHeaders, hex(sha256(body)),
        ).joinToString("\n")
        val scope = "$day/$region/$service/aws4_request"
        val stringToSign = listOf("AWS4-HMAC-SHA256", date, scope, hex(sha256(canonicalRequest.toByteArray()))).joinToString("\n")
        var key = hmac(("AWS4" + credentials.secretKey).toByteArray(), day)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, stringToSign))
        val out = linkedMapOf("x-amz-date" to date)
        if (credentials.sessionToken.isNotEmpty()) out["x-amz-security-token"] = credentials.sessionToken
        out["authorization"] =
            "AWS4-HMAC-SHA256 Credential=${credentials.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
        return out
    }
    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())
    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
