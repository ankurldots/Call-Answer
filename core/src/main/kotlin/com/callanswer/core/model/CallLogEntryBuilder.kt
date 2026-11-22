package com.callanswer.core.model

import com.callanswer.core.engine.CallSession
import com.callanswer.core.engine.ConversationState

/** Builds the Call_Log entry from the final conversation state and the call session (10.1, 10.2, 10.9, 10.12, 13.18). */
object CallLogEntryBuilder {
    const val MAX_DURATION_SEC = 86_400

    /**
     * [endedAtMs] is the end time on the event clock (milliseconds since the call started).
     * [folderSelected] false gives NO_FOLDER_SELECTED (12.7); otherwise the recording starts PENDING.
     * A null caller number stays null (shown as unknown, 10.2).
     */
    fun build(
        session: CallSession,
        state: ConversationState,
        endedAtMs: Long,
        outcome: Outcome,
        endReason: EndReason,
        notes: List<LogNoteKind> = emptyList(),
        voiceSubstitutions: List<VoiceSubstitution> = emptyList(),
        folderSelected: Boolean = true,
    ): CallLogEntry {
        val stalling = state.stallStartMs?.let { ((endedAtMs - it).coerceAtLeast(0L) / 1000L).toInt() }
        return CallLogEntry(
            id = session.id,
            callerNumber = session.callerNumber,
            startedAtEpochMs = session.startedAt.toEpochMilli(),
            zoneId = session.zone.id,
            durationSec = (endedAtMs.coerceAtLeast(0L) / 1000L).coerceAtMost(MAX_DURATION_SEC.toLong()).toInt(),
            language = state.lastLanguage,
            intent = state.intent,
            outcome = outcome,
            endReason = endReason,
            transcript = state.transcript,
            gateStatements = state.gateStatements,
            voiceGender = session.frozen.voiceGender,
            voiceSubstitutions = voiceSubstitutions,
            stallingDurationSec = stalling,
            takeovers = state.takeovers,
            interruption = state.interruption,
            recording = RecordingInfo(if (folderSelected) RecordingStatus.PENDING else RecordingStatus.NO_FOLDER_SELECTED),
            notes = notes,
            transfer = state.transfer?.let {
                TransferRecord(
                    path = it.path,
                    alertReason = it.alertReason,
                    outcome = it.outcome ?: TransferOutcome.CALL_ENDED_WHILE_WAITING,
                    startedAtMs = it.startedAtMs,
                    alertRaisedAtMs = it.alertRaisedAtMs,
                )
            },
            report = SpamReportInfo(),
        )
    }
}

/** Display order of the Call_Log: newest first (10.4). */
object CallLogOrder {
    fun sortForDisplay(entries: List<CallLogEntry>): List<CallLogEntry> =
        entries.sortedByDescending { it.startedAtEpochMs }
}

/** JSON encoding of a Call_Log entry with [CoreJson]. */
object CallLogJson {
    fun encode(entry: CallLogEntry): String = CoreJson.encodeToString(CallLogEntry.serializer(), entry)

    fun decode(json: String): CallLogEntry = CoreJson.decodeFromString(CallLogEntry.serializer(), json)
}
