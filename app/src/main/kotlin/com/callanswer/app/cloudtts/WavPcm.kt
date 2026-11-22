package com.callanswer.app.cloudtts
import java.nio.ByteBuffer
import java.nio.ByteOrder
/** Reads the sample data of a mono PCM16 WAV. */
internal object WavPcm {
    /** Returns the 16-bit samples of the `data` chunk, or null when [wav] is not a PCM16 WAV. */
    fun samples(wav: ByteArray): ShortArray? {
        if (wav.size < 12 || String(wav, 0, 4) != "RIFF" || String(wav, 8, 4) != "WAVE") return null
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4)
            val size = buf.getInt(pos + 4)
            if (size < 0) return null
            if (id == "data") {
                val end = minOf(pos + 8 + size, wav.size)
                val out = ShortArray((end - pos - 8) / 2)
                buf.position(pos + 8)
                buf.asShortBuffer().get(out)
                return out
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }
}
