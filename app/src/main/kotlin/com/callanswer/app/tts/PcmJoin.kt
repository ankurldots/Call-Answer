package com.callanswer.app.tts

/** PCM helpers for joining per-script runs into one buffer. */
internal object PcmJoin {
    /** Linear-interpolation resample of 16-bit mono PCM. */
    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || pcm.isEmpty()) return pcm
        val n = (pcm.size.toLong() * to / from).toInt()
        val out = ShortArray(n)
        val ratio = from.toDouble() / to
        for (i in 0 until n) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val f = pos - i0
            out[i] = (pcm[i0] * (1 - f) + pcm[i1] * f).toInt().toShort()
        }
        return out
    }

    /** Concatenates parts after resampling each to [rate]. */
    fun concat(parts: List<Pair<ShortArray, Int>>, rate: Int): ShortArray {
        val resampled = parts.map { (pcm, r) -> resample(pcm, r, rate) }
        val out = ShortArray(resampled.sumOf { it.size })
        var at = 0
        for (p in resampled) { p.copyInto(out, at); at += p.size }
        return out
    }

    fun floatToPcm16(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
}
