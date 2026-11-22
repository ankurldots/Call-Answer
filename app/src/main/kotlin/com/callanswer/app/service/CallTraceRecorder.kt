package com.callanswer.app.service

import java.util.concurrent.CopyOnWriteArrayList

/** One line of the call trace: an engine event or an effect, with the time since the call started. */
data class TraceEntry(val atMs: Long, val kind: String, val name: String, val detail: String)

/**
 * Debug record of one call: every engine event and effect with its time in milliseconds since the call
 * started, used to check the 1 s, 2 s, 3 s and 500 ms limits against the scenario checklist
 * (docs/e2e-scenarios.md). Entries are kept in memory only until exported; [export] renders tab-separated text.
 */
class CallTraceRecorder {
    private val entries = CopyOnWriteArrayList<TraceEntry>()

    fun clear() = entries.clear()

    fun event(atMs: Long, event: Any) = add(atMs, "EVENT", event)

    fun effect(atMs: Long, effect: Any) = add(atMs, "EFFECT", effect)

    private fun add(atMs: Long, kind: String, item: Any) {
        val name = item::class.simpleName ?: "?"
        entries += TraceEntry(atMs, kind, name, item.toString().take(MAX_DETAIL))
    }

    fun snapshot(): List<TraceEntry> = entries.toList()

    /** Tab-separated lines `atMs, kind, name, detail`, preceded by a header, followed by the gap since the previous line. */
    fun export(sessionId: String?): String = buildString {
        appendLine("# call trace session=${sessionId ?: "-"}")
        appendLine("atMs\tdeltaMs\tkind\tname\tdetail")
        var prev = 0L
        for (e in entries) {
            appendLine("${e.atMs}\t${e.atMs - prev}\t${e.kind}\t${e.name}\t${e.detail.replace('\n', ' ').replace('\t', ' ')}")
            prev = e.atMs
        }
    }

    private companion object {
        const val MAX_DETAIL = 200
    }
}
