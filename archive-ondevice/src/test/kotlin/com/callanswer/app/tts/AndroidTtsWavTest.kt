package com.callanswer.app.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidTtsWavTest {
    private fun chunk(tag: String, body: ByteArray, declaredSize: Int = body.size): ByteArray {
        val bb = ByteBuffer.allocate(8 + body.size + (body.size and 1)).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(tag.toByteArray(Charsets.US_ASCII)).putInt(declaredSize).put(body)
        return bb.array()
    }

    private fun wav(rate: Int, samples: ShortArray, extra: Boolean, dataSize: Int = samples.size * 2): ByteArray {
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).array()
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putShort(it) } }.array()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray()); out.write(ByteArray(4)); out.write("WAVE".toByteArray())
        out.write(chunk("fmt ", fmt))
        if (extra) out.write(chunk("LIST", "INFOISFT\u0005\u0000\u0000\u0000Lavf\u0000".toByteArray(Charsets.ISO_8859_1)))
        out.write(chunk("data", data, dataSize))
        return out.toByteArray()
    }

    @Test
    fun parsesLISTChunkAnd24kHz() {
        val pcm = shortArrayOf(1, -2, 300, -400, 32767)
        val (out, rate) = AndroidTtsSynthesizer.readWav(wav(24000, pcm, extra = true))
        assertEquals(24000, rate)
        assertArrayEquals(pcm, out)
    }

    @Test
    fun streamingDataSizeUsesRestOfFile() {
        val pcm = shortArrayOf(5, 6, 7)
        val (out, rate) = AndroidTtsSynthesizer.readWav(wav(22050, pcm, extra = false, dataSize = 0))
        assertEquals(22050, rate)
        assertArrayEquals(pcm, out)
    }

    @Test(expected = IllegalStateException::class)
    fun missingDataChunkThrows() {
        AndroidTtsSynthesizer.readWav("RIFF".toByteArray() + ByteArray(8))
    }
}
