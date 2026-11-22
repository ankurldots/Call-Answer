package com.callanswer.app.audio

import com.callanswer.core.engine.ConversationEvent

/** One utterance of Caller audio: 16 kHz mono PCM16 between [startMs] and [endMs] (same clock as [PcmFrame.timestampMs]). */
class SpeechSegment(val startMs: Long, val endMs: Long, val samples: ShortArray)

/** Tells the segmenter whether a time range lies inside an Agent window (implemented by `EchoGate`). */
fun interface AgentWindowGate {
    fun isFullyInAgentWindow(startMs: Long, endMs: Long): Boolean
}

/**
 * Turns capture frames into speech segments using a [VoiceActivityDetector].
 *
 * - A segment ends after [hangoverMs] (400 ms) of non-speech.
 * - A segment that lies fully inside an Agent window is dropped and never reaches ASR.
 * - Speech outside Agent windows is reported through [onVoiceActivity] (the engine treats it as
 *   information only, never as an interrupt).
 * - [tick] builds the engine `Tick` with `silentForMs` and `noAudioForMs`. A frame of all zero samples
 *   counts as no audio (silenced capture).
 *
 * Not thread-safe: call from one coroutine.
 */
class SpeechSegmenter(
    private val vad: VoiceActivityDetector,
    private val gate: AgentWindowGate,
    startMs: Long,
    private val hangoverMs: Long = ENDPOINT_HANGOVER_MS,
    private val onVoiceActivity: (atMs: Long) -> Unit = {},
    private val onSegment: (SpeechSegment) -> Unit,
) {
    private val window = VoiceActivityDetector.WINDOW_SAMPLES
    private var pending = ShortArray(0)
    private var inSpeech = false
    private var segStartMs = 0L
    private var lastSpeechEndMs = 0L
    private var segment = ArrayList<ShortArray>()
    private var lastSpeechMs = startMs
    private var lastAudioMs = startMs

    fun onFrame(frame: PcmFrame) {
        if (frame.samples.any { it.toInt() != 0 }) lastAudioMs = frame.timestampMs
        val buf = pending + frame.samples
        val baseMs = frame.timestampMs - pending.size / SAMPLES_PER_MS
        var pos = 0
        while (pos + window <= buf.size) {
            val chunk = buf.copyOfRange(pos, pos + window)
            val winStart = baseMs + pos / SAMPLES_PER_MS
            val winEnd = winStart + window / SAMPLES_PER_MS
            process(chunk, winStart, winEnd)
            pos += window
        }
        pending = buf.copyOfRange(pos, buf.size)
    }

    /** Ends an open segment (call at the end of the call or when capture stops). */
    fun flush() {
        if (inSpeech) endSegment()
    }

    fun tick(nowMs: Long): ConversationEvent.Tick = ConversationEvent.Tick(
        atMs = nowMs,
        silentForMs = (nowMs - lastSpeechMs).coerceAtLeast(0),
        noAudioForMs = (nowMs - lastAudioMs).coerceAtLeast(0),
    )

    private fun process(chunk: ShortArray, startMs: Long, endMs: Long) {
        val speech = vad.isSpeech(FloatArray(chunk.size) { chunk[it] / 32768f })
        if (speech) {
            if (!inSpeech) {
                inSpeech = true
                segStartMs = startMs
                segment = ArrayList()
            }
            segment.add(chunk)
            lastSpeechEndMs = endMs
            if (!gate.isFullyInAgentWindow(startMs, endMs)) {
                lastSpeechMs = endMs
                onVoiceActivity(endMs)
            }
        } else if (inSpeech) {
            segment.add(chunk)
            if (endMs - lastSpeechEndMs >= hangoverMs) endSegment()
        }
    }

    private fun endSegment() {
        val chunks = segment
        val start = segStartMs
        val end = lastSpeechEndMs
        inSpeech = false
        segment = ArrayList()
        if (gate.isFullyInAgentWindow(start, end)) return
        val out = ShortArray(chunks.sumOf { it.size })
        var p = 0
        for (c in chunks) {
            c.copyInto(out, p)
            p += c.size
        }
        onSegment(SpeechSegment(start, end, out))
    }

    companion object {
        const val ENDPOINT_HANGOVER_MS = 400L
        private const val SAMPLES_PER_MS = 16
    }
}
