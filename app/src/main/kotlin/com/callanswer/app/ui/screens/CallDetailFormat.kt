package com.callanswer.app.ui.screens

import com.callanswer.core.model.CallLogEntry
import java.util.Locale

/** One line of the call timeline shown on the detail screen. [tMs] is milliseconds since the call started. */
data class TimelineEvent(val tMs: Long, val kind: Kind, val detail: String? = null) {
    enum class Kind { SONIC_START, SONIC_END, FALLBACK, TAKEOVER, INTERRUPTION, CALL_END }
}

/** Pure helpers for cost, latency, and timeline text of the call screens (12.10). */
object CallDetailFormat {
    /** Paise as rupees with two decimals, e.g. 1250 becomes "₹12.50". */
    fun inr(paise: Long): String = String.format(Locale.US, "₹%d.%02d", paise / 100, paise % 100)

    /** Milliseconds as seconds with one decimal, e.g. 1250 becomes "1.3 s". */
    fun seconds(ms: Long): String = String.format(Locale.US, "%.1f s", ms / 1000.0)

    /** Month total against the limit, as a fraction in 0..1, or null without a limit. */
    fun limitFraction(totalPaise: Long, limitInr: Long?): Float? =
        if (limitInr == null || limitInr <= 0) null else (totalPaise / (limitInr * 100.0)).coerceIn(0.0, 1.0).toFloat()

    /** Events of the entry in time order. The server keeps the full trace; the entry carries these. */
    fun timeline(e: CallLogEntry): List<TimelineEvent> {
        val out = ArrayList<TimelineEvent>()
        e.sonicSessions.forEach { s ->
            out += TimelineEvent(s.startMs, TimelineEvent.Kind.SONIC_START)
            s.endMs?.let { out += TimelineEvent(it, TimelineEvent.Kind.SONIC_END, s.reason) }
        }
        e.takeovers.forEach {
            out += TimelineEvent((it.startedAtEpochMs - e.startedAtEpochMs).coerceAtLeast(0), TimelineEvent.Kind.TAKEOVER, "${it.durationSec} s")
        }
        e.interruption?.let { out += TimelineEvent(it.startedAtMs, TimelineEvent.Kind.INTERRUPTION, "${it.durationMs} ms") }
        e.fallbackCause?.let { out += TimelineEvent(0, TimelineEvent.Kind.FALLBACK, it) }
        out += TimelineEvent(e.durationSec * 1000L, TimelineEvent.Kind.CALL_END)
        return out.sortedBy { it.tMs }
    }
}
