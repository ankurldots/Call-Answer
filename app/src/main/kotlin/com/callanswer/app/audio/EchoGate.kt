package com.callanswer.app.audio

import com.callanswer.core.echo.AgentWindow
import com.callanswer.core.echo.EchoTextFilter

/**
 * Marks audio inside Agent windows (`[ttsStart - 50 ms, ttsEnd + 600 ms]`) so that segments fully
 * covered by them are not sent to ASR. Alert tones are registered the same way. Times are on the
 * `SystemClock.elapsedRealtime()` clock, as in [PcmFrame.timestampMs].
 */
class EchoGate {
    private val lock = Any()
    private val windows = ArrayList<AgentWindow>()

    /** Registers the window for audio played from [ttsStartMs] to [ttsEndMs]. */
    fun markPlayback(ttsStartMs: Long, ttsEndMs: Long) = add(
        AgentWindow(ttsStartMs - EchoTextFilter.WINDOW_LEAD_MS, ttsEndMs + EchoTextFilter.WINDOW_TAIL_MS),
    )

    /** Registers an alert tone as an Agent window. */
    fun markAlertTone(startMs: Long, endMs: Long) = markPlayback(startMs, endMs)

    fun add(window: AgentWindow) = synchronized(lock) {
        windows.add(window)
        if (windows.size > MAX_WINDOWS) windows.removeAt(0)
    }

    /** True when the frame start lies inside an Agent window. */
    fun isMarked(frame: PcmFrame): Boolean = synchronized(lock) {
        windows.any { frame.timestampMs >= it.startMs && frame.timestampMs <= it.endMs }
    }

    /** True when `[startMs, endMs]` is fully covered by the union of Agent windows: skip ASR for it. */
    fun isFullyCovered(startMs: Long, endMs: Long): Boolean = synchronized(lock) {
        var pos = startMs
        for (w in windows.sortedBy { it.startMs }) {
            if (w.endMs < pos) continue
            if (w.startMs > pos) return false
            pos = w.endMs + 1
            if (pos > endMs) return true
        }
        pos > endMs
    }

    fun clear() = synchronized(lock) { windows.clear() }

    private companion object {
        const val MAX_WINDOWS = 256
    }
}
