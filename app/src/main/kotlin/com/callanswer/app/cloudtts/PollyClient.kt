package com.callanswer.app.cloudtts
import android.util.Base64
import com.callanswer.app.auth.AuthConfig
import com.callanswer.app.auth.AuthException
import com.callanswer.app.auth.AuthRepository
import com.callanswer.app.auth.HttpsTransport
import com.callanswer.app.auth.SigV4
import com.callanswer.app.auth.Transport
import com.callanswer.core.model.Language
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant
class PollyException(val kind: Kind) : Exception(kind.name) {
    enum class Kind { NOT_SIGNED_IN, RATE_LIMITED, DENIED, NETWORK, SERVER }
}
/** Calls the signed `/tts` API. Output is mono PCM16 at [SAMPLE_RATE]. */
class PollyClient(
    private val config: AuthConfig,
    private val auth: AuthRepository,
    private val transport: Transport = HttpsTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Instant = Instant::now,
    private val decodeBase64: (String) -> ByteArray = { Base64.decode(it, Base64.DEFAULT) },
) {
    suspend fun fetch(text: String, language: Language): ShortArray {
        val credentials = try {
            auth.credentials()
        } catch (e: AuthException) {
            throw PollyException(if (e.error == com.callanswer.app.auth.AuthError.NETWORK) PollyException.Kind.NETWORK else PollyException.Kind.NOT_SIGNED_IN)
        }
        val body = buildJsonObject {
            put("text", text)
            put("language_code", languageCode(language))
        }.toString().toByteArray(Charsets.UTF_8)
        val headers = linkedMapOf("content-type" to "application/json")
        headers += SigV4.sign("POST", config.apiUrl, headers, body, credentials, config.region, "execute-api", now())
        val (status, response) = try {
            withContext(io) { transport.post(config.apiUrl, headers, body.toString(Charsets.UTF_8)) }
        } catch (e: IOException) {
            throw PollyException(PollyException.Kind.NETWORK)
        }
        when (status) {
            200 -> Unit
            429 -> throw PollyException(PollyException.Kind.RATE_LIMITED)
            401, 403 -> throw PollyException(PollyException.Kind.DENIED)
            else -> throw PollyException(PollyException.Kind.SERVER)
        }
        val wav = runCatching {
            decodeBase64(Json.parseToJsonElement(response).jsonObject.getValue("audio_base64").jsonPrimitive.content)
        }.getOrNull() ?: throw PollyException(PollyException.Kind.SERVER)
        return WavPcm.samples(wav) ?: throw PollyException(PollyException.Kind.SERVER)
    }
    companion object {
        const val SAMPLE_RATE = 16000
        fun languageCode(language: Language) = when (language) {
            Language.EN -> "en-IN"
            Language.HI -> "hi-IN"
        }
    }
}
