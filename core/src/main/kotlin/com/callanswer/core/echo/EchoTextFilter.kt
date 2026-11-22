package com.callanswer.core.echo

import com.callanswer.core.intent.TextNormalizer

/** One Agent utterance as played: text plus TTS start and end times (ms on the call clock). */
data class AgentUtterance(val text: String, val ttsStartMs: Long, val ttsEndMs: Long) {
    init {
        require(ttsEndMs >= ttsStartMs) { "ttsEndMs must be >= ttsStartMs" }
    }

    /** Agent window `[ttsStart - 50 ms, ttsEnd + 600 ms]`. */
    val window: AgentWindow
        get() = AgentWindow(ttsStartMs - EchoTextFilter.WINDOW_LEAD_MS, ttsEndMs + EchoTextFilter.WINDOW_TAIL_MS)
}

/** Closed time interval during which the microphone may carry the Agent's own voice. */
data class AgentWindow(val startMs: Long, val endMs: Long) {
    fun overlaps(segStartMs: Long, segEndMs: Long): Boolean = segStartMs <= endMs && segEndMs >= startMs
}

/** An ASR segment with its time span (ms on the call clock). */
data class AsrSegment(val text: String, val startMs: Long, val endMs: Long)

/**
 * Third echo layer: drops ASR segments that overlap an Agent window and whose normalized
 * token similarity to an Agent utterance spoken in the last 15 s is at least 0.7.
 *
 * Similarity is the share of the segment's distinct tokens that also occur in the Agent
 * utterance, so a segment holding only part of the utterance still matches.
 */
object EchoTextFilter {
    const val WINDOW_LEAD_MS = 50L
    const val WINDOW_TAIL_MS = 600L
    const val LOOK_BACK_MS = 15_000L
    const val THRESHOLD = 0.7

    private const val EPS = 1e-9

    fun tokens(text: String): List<String> =
        TextNormalizer.normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Share of distinct segment tokens found in the utterance; 0.0 when either side has no tokens. */
    fun similarity(segmentText: String, agentText: String): Double {
        val seg = tokens(segmentText).toSet()
        val agent = tokens(agentText).toSet()
        if (seg.isEmpty() || agent.isEmpty()) return 0.0
        return seg.count { it in agent }.toDouble() / seg.size
    }

    fun isEcho(segment: AsrSegment, agentUtterances: List<AgentUtterance>): Boolean =
        agentUtterances.any { u ->
            u.window.overlaps(segment.startMs, segment.endMs) &&
                u.ttsEndMs >= segment.endMs - LOOK_BACK_MS &&
                similarity(segment.text, u.text) >= THRESHOLD - EPS
        }

    /** Returns the segments that are not echo, in input order. */
    fun filter(segments: List<AsrSegment>, agentUtterances: List<AgentUtterance>): List<AsrSegment> =
        segments.filterNot { isEcho(it, agentUtterances) }
}
