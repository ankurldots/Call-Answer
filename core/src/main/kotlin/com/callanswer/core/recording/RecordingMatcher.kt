package com.callanswer.core.recording

import com.callanswer.core.phone.PhoneNumberMatcher
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlin.math.abs

data class RecordingCandidate(
    val uri: String,
    val name: String,
    val lastModifiedMs: Long,
    val sizeBytes: Long,
    val startMsFromName: Long?,
    val mimeType: String? = null,
)

object RecordingMatcher {
    private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "amr", "3gp", "wav")

    // Lenient: 6 or 8 digit date, a separator, then 6 digit time (e.g. 240131_142530, 20240131-142530).
    private val NAME_TIMESTAMP = Regex("""(?<!\d)(\d{8}|\d{6})[_\-\s.T]?(\d{6})(?!\d)""")

    fun isAudio(c: RecordingCandidate): Boolean {
        if (c.mimeType?.lowercase()?.startsWith("audio/") == true) return true
        val ext = c.name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    /** Parses a start time from a recording file name; null when no timestamp is found or it is invalid. */
    fun parseStartMsFromName(name: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val base = name.substringBeforeLast('.', name)
        for (m in NAME_TIMESTAMP.findAll(base)) {
            val (date, time) = m.destructured
            val pattern = if (date.length == 8) "uuuuMMddHHmmss" else "uuMMddHHmmss"
            try {
                val fmt = DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)
                val ldt = LocalDateTime.parse(date + time, fmt)
                return ldt.atZone(zone).toInstant().toEpochMilli()
            } catch (_: Exception) {
                // try the next match
            }
        }
        return null
    }

    fun choose(
        candidates: List<RecordingCandidate>,
        alreadyLinked: Set<String>,
        callerNumber: String?,
        callStartMs: Long,
        callEndMs: Long,
        windowMs: Long = 60_000,
    ): RecordingCandidate? {
        fun start(c: RecordingCandidate) = c.startMsFromName ?: c.lastModifiedMs

        val inWindow = candidates.filter { c ->
            isAudio(c) && c.uri !in alreadyLinked &&
                (abs(start(c) - callStartMs) <= windowMs || abs(c.lastModifiedMs - callEndMs) <= windowMs)
        }
        if (inWindow.isEmpty()) return null

        val key = callerNumber?.let { PhoneNumberMatcher.matchKey(it) }.orEmpty()
        val pool = if (key.isNotEmpty()) {
            val withNumber = inWindow.filter { c -> c.name.filter { it in '0'..'9' }.contains(key) }
            withNumber.ifEmpty { inWindow }
        } else {
            inWindow
        }

        return pool.minWithOrNull(
            compareBy<RecordingCandidate> { abs(start(it) - callStartMs) }
                .thenBy { abs(it.lastModifiedMs - callEndMs) }
                .thenBy { it.name }
                .thenBy { it.uri },
        )
    }
}
