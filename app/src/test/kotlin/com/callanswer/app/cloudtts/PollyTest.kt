package com.callanswer.app.cloudtts
import com.callanswer.app.auth.AuthConfig
import com.callanswer.app.auth.AuthRepository
import com.callanswer.app.auth.CognitoApi
import com.callanswer.app.auth.StoredSession
import com.callanswer.app.auth.TokenStore
import com.callanswer.app.auth.Transport
import com.callanswer.core.model.QuickReply
import com.callanswer.core.model.Settings
import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
class PollyTest {
    private val config = AuthConfig("ap-south-1", "pool", "client", "idpool", "https://api.example.com/prod/tts")
    private val samples = shortArrayOf(1, -2, 300, -400)
    private fun wav(s: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(s) }.array()
        val out = ByteArrayOutputStream()
        fun le(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write("RIFF".toByteArray()); out.write(le(36 + data.size)); out.write("WAVEfmt ".toByteArray())
        out.write(le(16)); out.write(byteArrayOf(1, 0, 1, 0)); out.write(le(16000)); out.write(le(32000)); out.write(byteArrayOf(2, 0, 16, 0))
        out.write("data".toByteArray()); out.write(le(data.size)); out.write(data)
        return out.toByteArray()
    }
    private class Store : TokenStore {
        var s: StoredSession? = StoredSession("a@b.com", "r")
        override fun load() = s
        override fun save(session: StoredSession) { s = session }
        override fun clear() { s = null }
    }
    private fun authTransport() = Transport { _, h, _ ->
        200 to when (h.getValue("X-Amz-Target").substringAfterLast('.')) {
            "InitiateAuth" -> """{"AuthenticationResult":{"IdToken":"id","ExpiresIn":3600}}"""
            "GetId" -> """{"IdentityId":"i"}"""
            else -> """{"Credentials":{"AccessKeyId":"AK","SecretKey":"SK","SessionToken":"ST","Expiration":4102444800.0}}"""
        }
    }
    private fun client(api: Transport): PollyClient {
        val auth = AuthRepository(config, Store(), CognitoApi(config, authTransport()), Dispatchers.Unconfined)
        return PollyClient(config, auth, api, Dispatchers.Unconfined, decodeBase64 = { Base64.getDecoder().decode(it) })
    }
    private fun ok(w: ByteArray) = """{"audio_base64":"${Base64.getEncoder().encodeToString(w)}"}"""
    @Test fun wavParsing() {
        assertArrayEquals(samples, WavPcm.samples(wav(samples)))
        assertNull(WavPcm.samples("nope".toByteArray()))
    }
    @Test fun fetchSignsRequestAndDecodesAudio() = runBlocking {
        var seen: Map<String, String> = emptyMap()
        var body = ""
        val pcm = client { _, h, b -> seen = h; body = b; 200 to ok(wav(samples)) }.fetch("Hello", Language.EN)
        assertArrayEquals(samples, pcm)
        assertTrue(seen.getValue("authorization").startsWith("AWS4-HMAC-SHA256 Credential=AK/"))
        assertEquals("ST", seen["x-amz-security-token"])
        assertTrue(body.contains("\"language_code\":\"en-IN\""))
    }
    @Test fun statusCodesMapToKinds() = runBlocking {
        for ((status, kind) in listOf(429 to PollyException.Kind.RATE_LIMITED, 403 to PollyException.Kind.DENIED, 500 to PollyException.Kind.SERVER)) {
            try { client { _, _, _ -> status to "{}" }.fetch("x", Language.EN); fail() } catch (e: PollyException) { assertEquals(kind, e.kind) }
        }
    }
    private fun tempCache() = PollyAudioCache(File.createTempFile("polly", "").let { it.delete(); it.mkdirs(); it })
    @Test fun cacheRoundTripAndPrune() {
        val c = tempCache()
        c.put("Hi.", Language.EN, samples)
        assertArrayEquals(samples, c.get("Hi.", Language.EN))
        assertNull(c.get("Hi.", Language.HI))
        c.retainOnly(emptySet())
        assertFalse(c.has("Hi.", Language.EN))
    }
    @Test fun cacheSynthesizerServesFemaleOnlyAndMissesThrow() = runBlocking {
        val c = tempCache(); c.put("Hi.", Language.EN, samples)
        val s = PollyCacheSynthesizer(c)
        assertArrayEquals(samples, s.synthesize("Hi.", Language.EN, VoiceGender.FEMALE).pcm)
        for ((t, g) in listOf("Other." to VoiceGender.FEMALE, "Hi." to VoiceGender.MALE)) {
            try { s.synthesize(t, Language.EN, g); fail() } catch (_: NoSuchElementException) {}
        }
    }
    @Test fun prefetchFetchesOnlyMissingPrunesAndStopsOnLimit() = runBlocking {
        val settings = Settings()
        val expected = SpokenTextCatalog.sentences(settings).size
        val cache = tempCache()
        var calls = 0
        val prefetcher = PollyPrefetcher(client { _, _, _ -> calls++; 200 to ok(wav(samples)) }, cache)
        val first = prefetcher.refresh(settings)
        assertEquals(expected, first.fetched)
        assertNull(first.stoppedBy)
        val again = prefetcher.refresh(settings)
        assertEquals(0, again.fetched); assertEquals(expected, again.alreadyCached); assertEquals(expected, calls)
        // Changing one quick reply fetches only that sentence (both languages) and drops the old audio.
        val changed = settings.copy(quickReplies = listOf(QuickReply("Leave it at the door.", "दरवाज़े पर रख दीजिए।")) + settings.quickReplies.drop(1))
        val third = prefetcher.refresh(changed)
        assertEquals(2, third.fetched)
        assertFalse(cache.has("Please wait", Language.EN))
        assertTrue(cache.has("Leave it at the door.", Language.EN))
        val limited = PollyPrefetcher(client { _, _, _ -> 429 to "{}" }, tempCache()).refresh(settings)
        assertEquals(PollyException.Kind.RATE_LIMITED, limited.stoppedBy)
    }
}
