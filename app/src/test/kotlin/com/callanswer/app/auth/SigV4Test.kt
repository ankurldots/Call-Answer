package com.callanswer.app.auth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
class SigV4Test {
    private val creds = AwsCredentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", "", 0)
    private val now = Instant.parse("2015-08-30T12:36:00Z")
    /** "get-vanilla" case of the public AWS Signature V4 test suite. */
    @Test fun awsGetVanillaVector() {
        val h = SigV4.sign("GET", "https://example.amazonaws.com/", emptyMap(), ByteArray(0), creds, "us-east-1", "service", now)
        assertEquals("20150830T123600Z", h["x-amz-date"])
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            h["authorization"],
        )
    }
    @Test fun sessionTokenIsSignedAndReturned() {
        val c = AwsCredentials("AKID", "secret", "TOKEN", 0)
        val h = SigV4.sign("POST", "https://abc.execute-api.ap-south-1.amazonaws.com/prod/tts", mapOf("content-type" to "application/json"),
            "{}".toByteArray(), c, "ap-south-1", "execute-api", now)
        assertEquals("TOKEN", h["x-amz-security-token"])
        assertTrue(h.getValue("authorization").contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token"))
    }
    @Test fun bodyChangesSignature() {
        fun sig(body: String) = SigV4.sign("POST", "https://h.example.com/p", emptyMap(), body.toByteArray(), creds, "r", "s", now)["authorization"]
        assertTrue(sig("a") != sig("b"))
    }
}
