package com.callanswer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Utterance(
    val speaker: Speaker,
    val text: String,
    val language: Language?,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class GateStatement(val gateNumber: String, val proactive: Boolean, val fictitious: Boolean, val atMs: Long)

@Serializable
data class TakeoverRecord(val startedAtEpochMs: Long, val zoneId: String, val durationSec: Int)

@Serializable
data class InterruptionRecord(val startedAtMs: Long, val durationMs: Long)

@Serializable
data class VoiceSubstitution(val language: Language, val usedVoiceId: String)

@Serializable
data class TransferRecord(
    val path: TransferPath,
    val alertReason: AlertReason?,
    val outcome: TransferOutcome,
    val startedAtMs: Long,
    val alertRaisedAtMs: Long?,
)

@Serializable
data class RecordingInfo(
    val status: RecordingStatus,
    val uri: String? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
)

@Serializable
data class ReportStamp(val status: ReportStatus, val atEpochMs: Long?, val zoneId: String?)

@Serializable
data class SpamReportInfo(
    val status: ReportStatus = ReportStatus.NOT_REPORTED,
    val reportedAtEpochMs: Long? = null,
    val reportedZoneId: String? = null,
    val attemptId: String? = null,
    val dispatched: Boolean = false,
    val pendingRestore: ReportStamp? = null,
)

@Serializable
data class CallLogEntry(
    val id: String,
    val callerNumber: String?,
    val startedAtEpochMs: Long,
    val zoneId: String,
    val durationSec: Int,
    val language: Language?,
    val intent: IntentCategory,
    val outcome: Outcome,
    val endReason: EndReason,
    val transcript: List<Utterance>,
    val gateStatements: List<GateStatement>,
    val voiceGender: VoiceGender,
    val voiceSubstitutions: List<VoiceSubstitution>,
    val stallingDurationSec: Int?,
    val takeovers: List<TakeoverRecord>,
    val interruption: InterruptionRecord?,
    val recording: RecordingInfo,
    val notes: List<LogNoteKind>,
    val transfer: TransferRecord? = null,
    val report: SpamReportInfo = SpamReportInfo(),
    // Cloud Call Agent fields. All have defaults so stored entries without them still decode.
    val costEstimatePaise: Long? = null,
    val latency: LatencyReport? = null,
    val sonicSessions: List<SonicSessionRecord> = emptyList(),
    val fallbackCause: String? = null,
    val recordingKey: String? = null,
    val isTestCall: Boolean = false,
    val voiceId: String? = null,
    /** Version of the settings the call was frozen from. */
    val settingsVersion: Long? = null,
)
