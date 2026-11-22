package com.callanswer.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.RecordingStatus
import com.callanswer.core.model.ReportStamp
import com.callanswer.core.model.ReportStatus
import com.callanswer.core.model.SpamReportInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@Dao
abstract class CallLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insert(row: CallLogEntity)

    /** Newest first (10.4). */
    @Query("SELECT * FROM call_log ORDER BY startedAtEpochMs DESC, id DESC")
    abstract suspend fun listRows(): List<CallLogEntity>

    @Query("SELECT * FROM call_log WHERE id = :id")
    abstract suspend fun getRow(id: String): CallLogEntity?

    @Query("DELETE FROM call_log")
    protected abstract suspend fun deleteAll(): Int

    /** Replaces the whole cache with the call log received from the server (the server is the source of truth). */
    @Transaction
    open suspend fun replaceAll(entries: List<CallLogEntry>) {
        deleteAll()
        entries.forEach { insert(it.toEntity()) }
    }

    @Query("DELETE FROM call_log WHERE id = :id")
    abstract suspend fun deleteById(id: String): Int

    @Query("SELECT id FROM call_log WHERE recordingUri = :uri LIMIT 1")
    protected abstract suspend fun idByRecordingUri(uri: String): String?

    @Query(
        "UPDATE call_log SET recordingStatus = :status, recordingUri = :uri, " +
            "recordingDurationMs = :durationMs, recordingSizeBytes = :sizeBytes WHERE id = :id",
    )
    protected abstract suspend fun setRecording(
        id: String, status: String, uri: String?, durationMs: Long?, sizeBytes: Long?,
    ): Int

    @Query("UPDATE call_log SET recordingStatus = :status WHERE id = :id")
    protected abstract suspend fun setRecordingStatus(id: String, status: String): Int

    @Query(
        "UPDATE call_log SET reportStatus = :newStatus, reportedAtEpochMs = :atMs, reportedZoneId = :zone, " +
            "reportAttemptId = :newAttemptId, reportDispatched = :dispatched, reportPendingRestore = :restore " +
            "WHERE id = :id AND reportStatus = :expectedStatus AND reportAttemptId IS :expectedAttemptId",
    )
    protected abstract suspend fun casReportRaw(
        id: String, expectedStatus: String, expectedAttemptId: String?,
        newStatus: String, atMs: Long?, zone: String?, newAttemptId: String?,
        dispatched: Boolean, restore: String?,
    ): Int

    suspend fun list(): List<CallLogEntry> = listRows().map { it.toEntry() }

    suspend fun get(id: String): CallLogEntry? = getRow(id)?.toEntry()

    /**
     * Writes the entry with up to [retries] retries spaced [retryDelayMs] apart (10.3). When every attempt
     * fails, [onNotLogged] is invoked (the "call not logged" notification hook) and false is returned.
     */
    suspend fun save(
        entry: CallLogEntry,
        retries: Int = SAVE_RETRIES,
        retryDelayMs: Long = SAVE_RETRY_DELAY_MS,
        onNotLogged: suspend (CallLogEntry, Throwable) -> Unit = { _, _ -> },
    ): Boolean {
        val row = entry.toEntity()
        var last: Throwable? = null
        for (attempt in 0..retries) {
            try {
                insert(row)
                return true
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                last = t
                if (attempt < retries) delay(retryDelayMs)
            }
        }
        onNotLogged(entry, last!!)
        return false
    }

    /**
     * Compare-and-set of the spam report columns. Updates only when both [expectedStatus] and
     * [expectedAttemptId] match; returns the number of updated rows (0 means another update won).
     */
    @Transaction
    open suspend fun casReport(
        id: String,
        expectedStatus: ReportStatus,
        expectedAttemptId: String?,
        new: SpamReportInfo,
    ): Int = casReportRaw(
        id = id,
        expectedStatus = expectedStatus.name,
        expectedAttemptId = expectedAttemptId,
        newStatus = new.status.name,
        atMs = new.reportedAtEpochMs,
        zone = new.reportedZoneId,
        newAttemptId = new.attemptId,
        dispatched = new.dispatched,
        restore = new.pendingRestore?.let { CoreJson.encodeToString(ReportStamp.serializer(), it) },
    )

    /**
     * Links a recording in one transaction. Returns false when the entry is missing or the unique index
     * on recordingUri shows the recording is already linked to another entry.
     */
    @Transaction
    open suspend fun linkRecording(id: String, uri: String, durationMs: Long?, sizeBytes: Long?): Boolean {
        val owner = idByRecordingUri(uri)
        if (owner != null && owner != id) return false
        return try {
            setRecording(id, RecordingStatus.LINKED.name, uri, durationMs, sizeBytes) == 1
        } catch (e: SQLiteConstraintException) {
            false
        }
    }

    suspend fun updateRecordingStatus(id: String, status: RecordingStatus): Int =
        setRecordingStatus(id, status.name)

    companion object {
        const val SAVE_RETRIES = 3
        const val SAVE_RETRY_DELAY_MS = 1_000L
    }
}
