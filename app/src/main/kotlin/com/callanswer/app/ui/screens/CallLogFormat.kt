package com.callanswer.app.ui.screens

import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.ReportStatus
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Pure helpers for the Call_Log screens. */
object CallLogFormat {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss z")

    /** Date and time with the timezone the call was recorded in (10.1). */
    fun dateTime(epochMs: Long, zoneId: String): String {
        val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneOffset.UTC)
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), zone).format(formatter)
    }

    /** Reverse chronological order, most recent first (10.4). */
    fun newestFirst(entries: List<CallLogEntry>): List<CallLogEntry> =
        entries.sortedWith(compareByDescending<CallLogEntry> { it.startedAtEpochMs }.thenByDescending { it.id })

    /** "Retry report" replaces nothing: it is shown in addition to "Report spam" for FAILED entries. */
    fun showRetry(status: ReportStatus): Boolean = status == ReportStatus.FAILED

    /** Readable form of an enum constant name, e.g. DELIVERY_DONE becomes "delivery done". */
    fun label(e: Enum<*>): String = e.name.lowercase().replace('_', ' ')
}
