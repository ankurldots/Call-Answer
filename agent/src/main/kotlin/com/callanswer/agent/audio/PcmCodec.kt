package com.callanswer.agent.audio

import java.util.Base64
import java.util.concurrent.atomic.AtomicLong

/**
 * Stream format announced in the Exotel start event (`media_format`).
 *
 * ASSUMPTION (spike C-1 not run): Exotel documents `encoding: "base64"`, `sample_rate` of
 * 8000/16000/24000 (as string) and 16-bit little-endian mono PCM inside the base64 payload.
 * Everything here is configurable so the spike result can change it without code changes.
 */
data class StreamFormat(
    val encoding: String = "base64",
    val sampleRate: Int = 8000,
    /**
     * Exact payload size in bytes per frame, or null when Exotel sends variable-size chunks.
     * ASSUMPTION: unknown until spike C-1 records chunk sizes.
     */
    val frameBytes: Int? = null,
    /** Encodings accepted as 16-bit LE mono PCM. ASSUMPTION: "base64" means raw PCM16. */
    val acceptedEncodings: Set<String> = setOf("base64", "pcm16", "audio/l16"),
) {
    /** Bytes for [millis] ms of audio at this format. */
    fun bytesForMillis(millis: Int): Int = sampleRate * millis / 1000 * 2
}

/** Base64 and PCM16 little-endian conversion plus frame validation. */
object PcmCodec {
    fun bytesToSamples(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0) { "PCM16 byte length must be even: ${bytes.size}" }
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            out[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    fun samplesToBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun encodeBase64(samples: ShortArray): String =
        Base64.getEncoder().encodeToString(samplesToBytes(samples))

    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Returns null when [payload] is not valid base64. */
    fun decodeBase64Bytes(payload: String): ByteArray? =
        try {
            Base64.getDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            null
        }

    /** Decodes a base64 PCM16 payload without format validation; null if malformed or odd length. */
    fun decodeBase64(payload: String): ShortArray? {
        val bytes = decodeBase64Bytes(payload) ?: return null
        if (bytes.size % 2 != 0) return null
        return bytesToSamples(bytes)
    }
}

/**
 * Validates inbound media payloads against the announced [StreamFormat].
 * A rejected frame is counted and returns null; it never alters how later frames are handled.
 */
class FrameValidator(val format: StreamFormat) {
    private val discarded = AtomicLong(0)

    /** Number of frames discarded so far (for the Call_Trace). */
    val discardedCount: Long get() = discarded.get()

    /** Returns the samples, or null (and counts a discard) if the frame does not match the format. */
    fun accept(payload: String, encoding: String? = null): ShortArray? {
        val enc = encoding ?: format.encoding
        if (enc.lowercase() !in format.acceptedEncodings) return discard()
        val bytes = PcmCodec.decodeBase64Bytes(payload) ?: return discard()
        if (bytes.isEmpty() || bytes.size % 2 != 0) return discard()
        val expected = format.frameBytes
        if (expected != null && bytes.size != expected) return discard()
        return PcmCodec.bytesToSamples(bytes)
    }

    private fun discard(): ShortArray? {
        discarded.incrementAndGet()
        return null
    }
}
