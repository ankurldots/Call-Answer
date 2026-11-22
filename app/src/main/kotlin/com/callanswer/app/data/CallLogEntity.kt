package com.callanswer.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Room row for one Call_Log entry: scalar columns for sorting/filtering, JSON columns for lists. */
@Entity(
    tableName = "call_log",
    indices = [
        Index(value = ["recordingUri"], unique = true),
        Index(value = ["startedAtEpochMs"]),
    ],
)
data class CallLogEntity(
    @PrimaryKey val id: String,
    val callerNumber: String?,
    val startedAtEpochMs: Long,
    val zoneId: String,
    val durationSec: Int,
    val language: String?,
    val intent: String,
    val outcome: String,
    val endReason: String,
    val voiceGender: String,
    val stallingDurationSec: Int?,
    val recordingStatus: String,
    val recordingUri: String?,
    val recordingDurationMs: Long?,
    val recordingSizeBytes: Long?,
    // JSON columns
    val transcript: String,
    val gateStatements: String,
    val takeovers: String,
    val notes: String,
    val transfer: String?,
    val voiceSubstitutions: String,
    val interruption: String?,
    // Spam report columns
    @ColumnInfo(defaultValue = "'NOT_REPORTED'") val reportStatus: String = "NOT_REPORTED",
    val reportedAtEpochMs: Long? = null,
    val reportedZoneId: String? = null,
    val reportAttemptId: String? = null,
    @ColumnInfo(defaultValue = "0") val reportDispatched: Boolean = false,
    val reportPendingRestore: String? = null,
)
