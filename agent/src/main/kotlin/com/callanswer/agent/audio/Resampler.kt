package com.callanswer.agent.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming integer-ratio resampler for 16-bit mono PCM (Requirements 3.2, 3.3, 3.6).
 *
 * The ratio is `toRate / fromRate` reduced to `up / down`. Samples are conceptually
 * upsampled by `up`, passed through a linear-phase windowed-sinc FIR low-pass filter
 * (cutoff at the lower of the two Nyquist frequencies), and decimated by `down`.
 * The filter delay is compensated, so output is time-aligned with the input.
 * Output is clipped to the 16-bit range.
 *
 * Feed chunks with [process]; call [flush] at the end of a stream to obtain the tail.
 * After [flush] the instance is reset. Not thread-safe.
 */
class Resampler(val fromRate: Int, val toRate: Int) {
    val up: Int
    val down: Int
    private val half: Int
    private val taps: FloatArray

    private var buf = FloatArray(0)
    private var bufLen = 0
    private var base = 0L        // absolute index of buf[0]
    private var totalIn = 0L     // samples received
    private var nextOut = 0L     // next output index

    init {
        require(fromRate > 0 && toRate > 0) { "rates must be positive" }
        val g = gcd(fromRate, toRate)
        up = toRate / g
        down = fromRate / g
        half = TAPS_PER_PHASE * maxOf(up, down)
        val cutoff = 0.5 / maxOf(up, down) // fraction of the upsampled rate
        val n = 2 * half + 1
        taps = FloatArray(n) { i ->
            val m = i - half
            val sinc = if (m == 0) 2 * cutoff else sin(2 * PI * cutoff * m) / (PI * m)
            val window = 0.54 + 0.46 * cos(PI * m / (half + 1)) // Hamming
            (sinc * window * up).toFloat()
        }
    }

    /** Resamples [input] and returns the samples that can be produced so far. */
    fun process(input: ShortArray): ShortArray {
        append(input)
        return drain(final = false)
    }

    /** Emits the remaining samples (input is treated as followed by silence) and resets state. */
    fun flush(): ShortArray {
        val out = drain(final = true)
        reset()
        return out
    }

    fun reset() {
        buf = FloatArray(0); bufLen = 0; base = 0; totalIn = 0; nextOut = 0
    }

    private fun append(input: ShortArray) {
        if (bufLen + input.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, bufLen + input.size))
        for (s in input) buf[bufLen++] = s.toFloat()
        totalIn += input.size
    }

    private fun sample(j: Long): Float {
        if (j < 0 || j >= totalIn) return 0f
        return buf[(j - base).toInt()]
    }

    private fun drain(final: Boolean): ShortArray {
        val out = ArrayList<Short>()
        while (true) {
            val c = nextOut * down
            if (final) {
                if (c >= totalIn * up) break
            } else {
                // need every input j <= floor((c + half) / up) to be present
                if ((c + half) / up >= totalIn) break
            }
            val jMin = maxOf(0L, ceilDiv(c - half, up.toLong()))
            val jMax = minOf(totalIn - 1, (c + half) / up)
            var acc = 0f
            var j = jMin
            while (j <= jMax) {
                acc += sample(j) * taps[(c + half - j * up).toInt()]
                j++
            }
            out.add(acc.roundToInt().coerceIn(-32768, 32767).toShort())
            nextOut++
        }
        compact()
        return out.toShortArray()
    }

    private fun compact() {
        val keepFrom = maxOf(0L, ceilDiv(nextOut * down - half, up.toLong()))
        val drop = (minOf(keepFrom, totalIn) - base).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            base += drop
        }
    }

    companion object {
        private const val TAPS_PER_PHASE = 12

        /** One-shot conversion of a complete buffer. */
        fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
            if (fromRate == toRate) return input.copyOf()
            val r = Resampler(fromRate, toRate)
            val a = r.process(input)
            val b = r.flush()
            return a + b
        }

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        private fun ceilDiv(a: Long, b: Long): Long = Math.floorDiv(a + b - 1, b)
    }
}
