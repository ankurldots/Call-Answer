package com.callanswer.agent.audio

import kotlin.math.sqrt

/**
 * Short-term energy detector on Caller audio (PCM16 little-endian, mono).
 *
 * Silence is counted from the moment the last Agent audio has played (the `mark` returned by
 * Exotel), so Caller silence during Agent speech does not count. Time is passed in by the
 * caller, so the detector is deterministic and needs no clock.
 *
 * Not thread-safe; use from the call's single audio coroutine.
 *
 * @param sampleRate Caller audio rate in Hz (for example 16000 after resampling).
 * @param frameMs Length of one energy frame in milliseconds.
 * @param rmsThreshold RMS (0..32767) at or above which a frame counts as speech.
 */
class SilenceDetector(
    private val sampleRate: Int = 16_000,
    private val frameMs: Int = 20,
    private val rmsThreshold: Double = 500.0,
) {
    private val frameSamples = sampleRate * frameMs / 1000
    private val frame = ShortArray(frameSamples)
    private var filled = 0

    private var counting = false
    private var silenceStartMs = 0L
    private var lastSpeechMs = Long.MIN_VALUE

    init {
        require(sampleRate > 0 && frameMs > 0 && frameSamples > 0) { "invalid frame configuration" }
        require(rmsThreshold >= 0.0) { "rmsThreshold must not be negative" }
    }

    /** True while the silence clock runs (after a mark, until the Caller speaks). */
    val isCounting: Boolean get() = counting

    /** The Agent's last audio has played (the `mark` arrived): start counting silence at [nowMs]. */
    fun onAgentAudioPlayed(nowMs: Long) {
        counting = true
        silenceStartMs = nowMs
    }

    /** Agent audio is playing or queued again: stop counting until the next mark. */
    fun onAgentAudioStarted() {
        counting = false
    }

    /** Feeds Caller PCM16 little-endian audio received at [nowMs]. Returns true if speech was heard. */
    fun onCallerAudio(pcm: ByteArray, nowMs: Long): Boolean {
        var speech = false
        var i = 0
        while (i + 1 < pcm.size) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
            frame[filled++] = s
            if (filled == frameSamples) {
                if (rms(frame) >= rmsThreshold) speech = true
                filled = 0
            }
            i += 2
        }
        if (speech) {
            lastSpeechMs = nowMs
            // Speech restarts the silence clock only if it is running.
            if (counting) silenceStartMs = nowMs
        }
        return speech
    }

    /** Milliseconds of Caller silence since the last mark or speech; 0 when not counting. */
    fun silentMs(nowMs: Long): Long = if (counting) maxOf(0L, nowMs - silenceStartMs) else 0L

    /** True when the Caller has been silent for at least [timeoutMs] since the last mark. */
    fun isTimedOut(nowMs: Long, timeoutMs: Long): Boolean = counting && nowMs - silenceStartMs >= timeoutMs

    /** Restarts the silence clock at [nowMs] after a re-prompt was issued (still counting). */
    fun restart(nowMs: Long) {
        counting = true
        silenceStartMs = nowMs
    }

    private fun rms(samples: ShortArray): Double {
        var sum = 0.0
        for (v in samples) sum += v.toDouble() * v
        return sqrt(sum / samples.size)
    }
}
