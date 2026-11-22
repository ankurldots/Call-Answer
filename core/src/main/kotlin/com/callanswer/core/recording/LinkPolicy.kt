package com.callanswer.core.recording

import com.callanswer.core.model.RecordingStatus

/** Outcome of one folder search attempt. */
sealed interface SearchOutcome {
    data object NoFolder : SearchOutcome
    data object AccessLost : SearchOutcome
    data class Found(val candidate: RecordingCandidate) : SearchOutcome
    data object NoMatch : SearchOutcome
}

object LinkPolicy {
    /** Search offsets after call end (12.5). */
    val SEARCH_SCHEDULE_MS: List<Long> = listOf(0L, 20_000L, 40_000L, 60_000L)

    /**
     * Status after an attempt. [attemptIndex] is the 0-based index into [SEARCH_SCHEDULE_MS].
     * PENDING means another attempt follows.
     */
    fun statusAfterAttempt(outcome: SearchOutcome, attemptIndex: Int): RecordingStatus = when (outcome) {
        SearchOutcome.NoFolder -> RecordingStatus.NO_FOLDER_SELECTED
        SearchOutcome.AccessLost -> RecordingStatus.FOLDER_ACCESS_LOST
        is SearchOutcome.Found -> RecordingStatus.LINKED
        SearchOutcome.NoMatch ->
            if (attemptIndex >= SEARCH_SCHEDULE_MS.lastIndex) RecordingStatus.NOT_FOUND else RecordingStatus.PENDING
    }

    /** True when the status ends the search; a notification is shown for NOT_FOUND and FOLDER_ACCESS_LOST (12.6, 12.11). */
    fun isFinal(status: RecordingStatus): Boolean = status != RecordingStatus.PENDING

    fun shouldNotify(status: RecordingStatus): Boolean =
        status == RecordingStatus.NOT_FOUND || status == RecordingStatus.FOLDER_ACCESS_LOST

    /**
     * Pure link step (12.16). Returns [links] unchanged when [entryId] is already linked to [uri] (idempotent)
     * or when [uri] is linked to another entry, or when [entryId] already has a different link.
     */
    fun applyLink(links: Map<String, String>, entryId: String, uri: String): Map<String, String> {
        val existing = links[entryId]
        if (existing != null) return links
        if (links.values.any { it == uri }) return links
        return links + (entryId to uri)
    }
}
