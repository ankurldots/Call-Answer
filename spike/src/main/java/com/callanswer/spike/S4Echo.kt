package com.callanswer.spike

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One Agent utterance on the capture timeline, in seconds since the capture started. */
data class AgentWindow(val ttsStartSec: Double, val ttsEndSec: Double, val text: String) {
    val gateStartSec: Double get() = ttsStartSec - EchoGate.PRE_SEC
    val gateEndSec: Double get() = ttsEndSec + EchoGate.POST_SEC
}

/** A stretch of speech found in the capture, in seconds since the capture started. */
data class CapSegment(val startSec: Double, val endSec: Double)

/**
 * Layer 1. Marks `[ttsStart - 50 ms, ttsEnd + 600 ms]` as an Agent window. A segment that lies fully inside
 * one window is not sent to ASR. A segment that sticks out of every window is kept, so a Caller who talks
 * over or after the Agent is still heard.
 */
class EchoGate(private val windows: List<AgentWindow>) {
    fun inAgentWindow(sec: Double) = windows.any { sec >= it.gateStartSec && sec <= it.gateEndSec }

    fun fullyInside(s: CapSegment) = windows.any { s.startSec >= it.gateStartSec && s.endSec <= it.gateEndSec }

    fun overlaps(s: CapSegment) = windows.any { s.endSec > it.gateStartSec && s.startSec < it.gateEndSec }

    companion object {
        const val PRE_SEC = 0.050
        const val POST_SEC = 0.600
    }
}

/**
 * Layer 3. Removes an ASR segment that overlaps an Agent window and whose tokens are similar (>= 0.7) to an
 * Agent utterance spoken in the last 15 s. Similarity = share of the segment's tokens that occur in the
 * Agent utterance (multiset containment), so a partial echo of a long sentence still matches.
 */
object EchoTextFilter {
    const val THRESHOLD = 0.7
    const val LOOKBACK_SEC = 15.0
    private val splitter = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    fun tokens(s: String): List<String> = s.lowercase().split(splitter).filter { it.isNotEmpty() }

    fun similarity(segment: String, agent: String): Double {
        val seg = tokens(segment)
        if (seg.isEmpty()) return 0.0
        val pool = HashMap<String, Int>()
        for (t in tokens(agent)) pool[t] = (pool[t] ?: 0) + 1
        var hit = 0
        for (t in seg) {
            val c = pool[t] ?: 0
            if (c > 0) { hit++; pool[t] = c - 1 }
        }
        return hit.toDouble() / seg.size
    }

    /** True if the segment should be dropped as echo. */
    fun isEcho(text: String, seg: CapSegment, gate: EchoGate, windows: List<AgentWindow>): Boolean {
        if (!gate.overlaps(seg)) return false
        return windows.any {
            it.ttsStartSec <= seg.endSec && seg.endSec - it.ttsEndSec <= LOOKBACK_SEC &&
                similarity(text, it.text) >= THRESHOLD
        }
    }

    /** Evaluation helper: does [agent] show up in [transcript]? Shared 3-gram, or 60% of Agent tokens covered. */
    fun agentTextFound(transcript: String, agent: String): Boolean {
        val a = tokens(agent)
        val t = tokens(transcript)
        if (a.isEmpty() || t.isEmpty()) return false
        val grams = a.windowed(3).toSet()
        if (t.windowed(3).any { it in grams }) return true
        val have = t.toSet()
        return a.count { it in have }.toDouble() / a.size >= 0.6
    }
}

/** Energy based speech segmentation over 16 kHz PCM16. Good enough to cut a turn into utterances. */
object S4Segmenter {
    private const val RATE = 16_000
    private const val FRAME = 320 // 20 ms

    fun segments(pcm: ShortArray, n: Int): List<CapSegment> {
        val frames = n / FRAME
        if (frames == 0) return emptyList()
        val rms = DoubleArray(frames) { f ->
            var q = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) q += pcm[i].toDouble() * pcm[i]
            sqrt(q / FRAME)
        }
        val sorted = rms.sortedArray()
        val p90 = sorted[(frames * 0.9).toInt().coerceAtMost(frames - 1)]
        val thr = max(80.0, p90 * 0.2)
        val out = ArrayList<CapSegment>()
        var start = -1
        var lastLoud = -1
        val gapFrames = 20 // 400 ms
        for (f in 0 until frames) {
            if (rms[f] >= thr) {
                if (start < 0) start = f
                lastLoud = f
            } else if (start >= 0 && f - lastLoud > gapFrames) {
                add(out, start, lastLoud, frames)
                start = -1
            }
        }
        if (start >= 0) add(out, start, lastLoud, frames)
        return out
    }

    private fun add(out: MutableList<CapSegment>, s: Int, e: Int, frames: Int) {
        if ((e - s + 1) < 12) return // under 240 ms
        val a = max(0, s - 5)
        val b = min(frames, e + 6)
        out.add(CapSegment(a * FRAME / RATE.toDouble(), b * FRAME / RATE.toDouble()))
    }
}

/**
 * Layer 2 prototype. Reference echo canceller with the TTS PCM as the render reference: delay by envelope
 * cross-correlation, then a normalised LMS filter. This is an in-app stand-in for WebRTC AEC3 / speexdsp,
 * which are native libraries that are not in the spike's dependencies. It answers the same question
 * (does subtracting the Agent's own signal help) with the same reference input.
 */
object S4Aec {
    private const val RATE = 16_000
    private const val TAPS = 1024
    private const val MU = 0.4

    /** Returns the cleaned PCM and the estimated delay in ms. [ref] is the Agent PCM at 16 kHz, played from [refStartSample]. */
    fun cancel(mic: ShortArray, n: Int, ref: FloatArray, refStartSample: Int): Pair<ShortArray, Int> {
        val delayMs = estimateDelayMs(mic, n, ref, refStartSample)
        // Start the reference 32 ms early so the filter has leading taps for estimation error.
        val shift = refStartSample + delayMs * 16 - 512
        val x = FloatArray(n)
        for (i in ref.indices) {
            val j = shift + i
            if (j in 0 until n) x[j] = ref[i]
        }
        val w = FloatArray(TAPS)
        val out = ShortArray(n)
        var energy = 0.0
        for (i in 0 until n) {
            energy += x[i].toDouble() * x[i]
            if (i >= TAPS) energy -= x[i - TAPS].toDouble() * x[i - TAPS]
            var y = 0f
            val m = min(TAPS, i + 1)
            for (k in 0 until m) y += w[k] * x[i - k]
            val e = mic[i] / 32768f - y
            val g = (MU * e / (energy + 1e-3)).toFloat()
            for (k in 0 until m) w[k] += g * x[i - k]
            out[i] = (e * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out to delayMs
    }

    private fun estimateDelayMs(mic: ShortArray, n: Int, ref: FloatArray, refStart: Int): Int {
        val envLen = n / 16
        val micEnv = DoubleArray(envLen) { f ->
            var s = 0.0
            for (i in f * 16 until f * 16 + 16) s += abs(mic[i].toInt()) / 32768.0
            s / 16
        }
        val refEnvLen = ref.size / 16
        val refEnv = DoubleArray(refEnvLen) { f ->
            var s = 0.0
            for (i in f * 16 until f * 16 + 16) s += abs(ref[i])
            s / 16
        }
        val base = refStart / 16
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in 0..500) {
            var score = 0.0
            for (f in 0 until refEnvLen) {
                val j = base + lag + f
                if (j >= envLen) break
                score += refEnv[f] * micEnv[j]
            }
            if (score > bestScore) { bestScore = score; best = lag }
        }
        return best
    }
}
