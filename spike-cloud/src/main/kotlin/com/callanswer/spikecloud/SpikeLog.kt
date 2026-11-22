package com.callanswer.spikecloud

import java.io.File
import java.time.Instant

/** Appends timestamped JSON lines to spike-cloud/results/<spike>.jsonl. */
class SpikeLog(private val spike: String, private val dir: File = File("spike-cloud/results")) {
    private val file: File by lazy { dir.apply { mkdirs() }.resolve("$spike.jsonl") }

    @Synchronized
    fun log(event: String, fields: Map<String, Any?> = emptyMap()) {
        file.appendText(line(event, fields) + "\n")
    }

    fun line(event: String, fields: Map<String, Any?> = emptyMap(), now: Instant = Instant.now()): String {
        val all = linkedMapOf<String, Any?>("ts" to now.toString(), "spike" to spike, "event" to event)
        all.putAll(fields)
        return all.entries.joinToString(prefix = "{", postfix = "}", separator = ",") {
            "\"${escape(it.key)}\":${value(it.value)}"
        }
    }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        else -> "\"${escape(v.toString())}\""
    }

    private fun escape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
}
