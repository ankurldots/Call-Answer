package com.callanswer.app.data

import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.GateStatement
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.InterruptionRecord
import com.callanswer.core.model.Language
import com.callanswer.core.model.LogNoteKind
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.RecordingInfo
import com.callanswer.core.model.RecordingStatus
import com.callanswer.core.model.ReportStamp
import com.callanswer.core.model.ReportStatus
import com.callanswer.core.model.SpamReportInfo
import com.callanswer.core.model.TakeoverRecord
import com.callanswer.core.model.TransferRecord
import com.callanswer.core.model.Utterance
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.model.VoiceSubstitution
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.encodeToString

object CallLogMapper {
    fun toEntity(e: CallLogEntry): CallLogEntity = CallLogEntity(
        id = e.id,
        callerNumber = e.callerNumber,
        startedAtEpochMs = e.startedAtEpochMs,
        zoneId = e.zoneId,
        durationSec = e.durationSec,
        language = e.language?.name,
        intent = e.intent.name,
        outcome = e.outcome.name,
        endReason = e.endReason.name,
        voiceGender = e.voiceGender.name,
        stallingDurationSec = e.stallingDurationSec,
        recordingStatus = e.recording.status.name,
        recordingUri = e.recording.uri,
        recordingDurationMs = e.recording.durationMs,
        recordingSizeBytes = e.recording.sizeBytes,
        transcript = CoreJson.encodeToString(ListSerializer(Utterance.serializer()), e.transcript),
        gateStatements = CoreJson.encodeToString(ListSerializer(GateStatement.serializer()), e.gateStatements),
        takeovers = CoreJson.encodeToString(ListSerializer(TakeoverRecord.serializer()), e.takeovers),
        notes = CoreJson.encodeToString(serializer<List<LogNoteKind>>(), e.notes),
        transfer = e.transfer?.let { CoreJson.encodeToString(TransferRecord.serializer(), it) },
        voiceSubstitutions = CoreJson.encodeToString(ListSerializer(VoiceSubstitution.serializer()), e.voiceSubstitutions),
        interruption = e.interruption?.let { CoreJson.encodeToString(InterruptionRecord.serializer(), it) },
        reportStatus = e.report.status.name,
        reportedAtEpochMs = e.report.reportedAtEpochMs,
        reportedZoneId = e.report.reportedZoneId,
        reportAttemptId = e.report.attemptId,
        reportDispatched = e.report.dispatched,
        reportPendingRestore = e.report.pendingRestore?.let { CoreJson.encodeToString(ReportStamp.serializer(), it) },
    )

    fun toEntry(r: CallLogEntity): CallLogEntry = CallLogEntry(
        id = r.id,
        callerNumber = r.callerNumber,
        startedAtEpochMs = r.startedAtEpochMs,
        zoneId = r.zoneId,
        durationSec = r.durationSec,
        language = r.language?.let { Language.valueOf(it) },
        intent = IntentCategory.valueOf(r.intent),
        outcome = Outcome.valueOf(r.outcome),
        endReason = EndReason.valueOf(r.endReason),
        transcript = CoreJson.decodeFromString(ListSerializer(Utterance.serializer()), r.transcript),
        gateStatements = CoreJson.decodeFromString(ListSerializer(GateStatement.serializer()), r.gateStatements),
        voiceGender = VoiceGender.valueOf(r.voiceGender),
        voiceSubstitutions = CoreJson.decodeFromString(ListSerializer(VoiceSubstitution.serializer()), r.voiceSubstitutions),
        stallingDurationSec = r.stallingDurationSec,
        takeovers = CoreJson.decodeFromString(ListSerializer(TakeoverRecord.serializer()), r.takeovers),
        interruption = r.interruption?.let { CoreJson.decodeFromString(InterruptionRecord.serializer(), it) },
        recording = RecordingInfo(
            status = RecordingStatus.valueOf(r.recordingStatus),
            uri = r.recordingUri,
            durationMs = r.recordingDurationMs,
            sizeBytes = r.recordingSizeBytes,
        ),
        notes = CoreJson.decodeFromString(serializer<List<LogNoteKind>>(), r.notes),
        transfer = r.transfer?.let { CoreJson.decodeFromString(TransferRecord.serializer(), it) },
        report = SpamReportInfo(
            status = ReportStatus.valueOf(r.reportStatus),
            reportedAtEpochMs = r.reportedAtEpochMs,
            reportedZoneId = r.reportedZoneId,
            attemptId = r.reportAttemptId,
            dispatched = r.reportDispatched,
            pendingRestore = r.reportPendingRestore?.let { CoreJson.decodeFromString(ReportStamp.serializer(), it) },
        ),
    )
}

fun CallLogEntry.toEntity(): CallLogEntity = CallLogMapper.toEntity(this)
fun CallLogEntity.toEntry(): CallLogEntry = CallLogMapper.toEntry(this)
