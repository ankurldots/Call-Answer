package com.callanswer.app.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.callanswer.app.data.CallAnswerDatabase
import com.callanswer.app.data.CallLogDao
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.service.NotificationCenter
import com.callanswer.core.model.RecordingStatus
import com.callanswer.core.recording.RecordingMatcher
import java.util.concurrent.TimeUnit

/** Outcome of one search pass. */
enum class LinkOutcome { LINKED, NOT_YET, NOT_FOUND, NO_FOLDER, ACCESS_LOST, SKIPPED }

/**
 * Finds the recording file of a call and links it to its Call_Log entry (12.1 to 12.7, 12.11, 12.15, 12.16).
 * Works fully offline (12.13) and never writes to the recording (12.12).
 */
class RecordingLinker(
    private val dao: CallLogDao,
    private val folderUri: suspend () -> String?,
    private val repository: RecordingFolderRepository,
    private val durationOf: (Uri) -> Long? = { null },
    private val notifyNotFound: (String) -> Unit = {},
    private val notifyAccessLost: () -> Unit = {},
) {
    /**
     * One search pass. [isLastAttempt] turns a miss into NOT_FOUND plus one notification (12.6).
     * With [manual] (Search again) a miss sets NOT_FOUND without a notification and the entry is
     * searched even when it is not PENDING.
     */
    suspend fun attempt(entryId: String, isLastAttempt: Boolean, manual: Boolean = false): LinkOutcome {
        val row = dao.getRow(entryId) ?: return LinkOutcome.SKIPPED
        val status = runCatching { RecordingStatus.valueOf(row.recordingStatus) }.getOrDefault(RecordingStatus.PENDING)
        if (!manual && status != RecordingStatus.PENDING) return LinkOutcome.SKIPPED
        if (manual && (status == RecordingStatus.LINKED || status == RecordingStatus.DELETED)) return LinkOutcome.SKIPPED

        val folder = folderUri()
        if (folder == null) {
            dao.updateRecordingStatus(entryId, RecordingStatus.NO_FOLDER_SELECTED)
            return LinkOutcome.NO_FOLDER
        }
        val candidates = when (val r = repository.listCandidates(Uri.parse(folder))) {
            is FolderResult.AccessLost -> {
                dao.updateRecordingStatus(entryId, RecordingStatus.FOLDER_ACCESS_LOST)
                notifyAccessLost()
                return LinkOutcome.ACCESS_LOST
            }
            is FolderResult.Ok -> r.value
        }
        val startMs = row.startedAtEpochMs
        val endMs = startMs + row.durationSec * 1000L
        val alreadyLinked = dao.listRows().mapNotNull { it.recordingUri }.toSet()
        val chosen = RecordingMatcher.choose(candidates, alreadyLinked, row.callerNumber, startMs, endMs)
        if (chosen != null) {
            val duration = durationOf(Uri.parse(chosen.uri))
            if (dao.linkRecording(entryId, chosen.uri, duration, chosen.sizeBytes)) return LinkOutcome.LINKED
            // Lost a race for the same file (12.16); treat as a miss.
        }
        if (!isLastAttempt && !manual) return LinkOutcome.NOT_YET
        dao.updateRecordingStatus(entryId, RecordingStatus.NOT_FOUND)
        if (!manual) notifyNotFound(row.callerNumber.orEmpty())
        return LinkOutcome.NOT_FOUND
    }

    companion object {
        /** Search times after call end (12.5). */
        val RETRY_DELAYS_SEC = longArrayOf(0, 20, 40, 60)
        const val KEY_ENTRY_ID = "entryId"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_MANUAL = "manual"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build()

        private fun workName(entryId: String, attempt: Int) = "recording-link-$entryId-$attempt"

        /** Schedules the searches at 0, 20, 40 and 60 s after call end. Call at call end for every entry. */
        fun schedule(context: Context, entryId: String) {
            val wm = WorkManager.getInstance(context.applicationContext)
            RETRY_DELAYS_SEC.forEachIndexed { i, delaySec ->
                wm.enqueueUniqueWork(workName(entryId, i), ExistingWorkPolicy.KEEP, request(entryId, i, false, delaySec))
            }
        }

        /** Manual "Search again" for an entry that was not linked. */
        fun searchAgain(context: Context, entryId: String) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "recording-link-$entryId-manual", ExistingWorkPolicy.REPLACE, request(entryId, RETRY_DELAYS_SEC.lastIndex, true, 0),
            )
        }

        private fun request(entryId: String, attempt: Int, manual: Boolean, delaySec: Long) =
            OneTimeWorkRequestBuilder<RecordingLinkWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delaySec, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ENTRY_ID to entryId, KEY_ATTEMPT to attempt, KEY_MANUAL to manual))
                .build()

        /** Reads the duration of the audio file; null when unavailable. */
        fun mediaDuration(context: Context, uri: Uri): Long? {
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(context, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
        }
    }
}

/** WorkManager worker that runs one [RecordingLinker.attempt]. */
class RecordingLinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entryId = inputData.getString(RecordingLinker.KEY_ENTRY_ID) ?: return Result.failure()
        val attempt = inputData.getInt(RecordingLinker.KEY_ATTEMPT, 0)
        val manual = inputData.getBoolean(RecordingLinker.KEY_MANUAL, false)
        val db = CallAnswerDatabase.create(applicationContext)
        try {
            val settings = SettingsStore.create(applicationContext)
            val center = NotificationCenter(applicationContext)
            val linker = RecordingLinker(
                dao = db.callLogDao(),
                folderUri = { settings.current().recordingFolderUri },
                repository = RecordingFolderRepository(applicationContext),
                durationOf = { RecordingLinker.mediaDuration(applicationContext, it) },
                notifyNotFound = { center.recordingNotFound(it) },
                notifyAccessLost = { center.folderAccessLost() },
            )
            linker.attempt(entryId, isLastAttempt = attempt >= RecordingLinker.RETRY_DELAYS_SEC.lastIndex, manual = manual)
            return Result.success()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            db.close()
        }
    }
}
