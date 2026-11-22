package com.callanswer.app.report

import android.content.Context
import com.callanswer.app.data.CallLogDao
import com.callanswer.app.data.SettingsStore
import com.callanswer.core.intent.PhraseTables
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.ReportStatus
import com.callanswer.core.model.SpamReportInfo
import com.callanswer.core.report.BriefDescriptionGenerator
import com.callanswer.core.report.DescriptionCheck
import com.callanswer.core.report.DescriptionError
import com.callanswer.core.report.ReportAction
import com.callanswer.core.report.ReportEvent
import com.callanswer.core.report.ReportFlowPolicy
import com.callanswer.core.report.ReportStatusMachine
import com.callanswer.core.report.ReportStep
import com.callanswer.core.report.SenderNumber
import com.callanswer.core.report.SpamReportBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What [ReportSpamActivity] shows after each [SpamReporter] call. */
sealed interface ReportUiState {
    /** Nothing to show: stale attempt, missing entry, or a flow that ended without a message. */
    data object Idle : ReportUiState

    /** The editable message (14.5). [validationError] is set when the last edit was rejected (14.9). */
    data class Confirm(
        val entryId: String,
        val attemptId: String,
        val text: String,
        val description: String,
        val truncated: Boolean,
        val permissionNote: Boolean,
        val validationError: DescriptionError? = null,
    ) : ReportUiState

    /** Repeat confirmation (14.26). Declining changes nothing. */
    data class AskRepeat(val status: ReportStatus, val reportedAtEpochMs: Long?) : ReportUiState

    data object CannotReport : ReportUiState
    data object HandedToMessagingApp : ReportUiState
    data object NoMessagingApp : ReportUiState
}

/**
 * Runs the spam report flow (Requirement 14). Decisions come from [ReportFlowPolicy] and
 * [ReportStatusMachine]; this class loads the entry, applies the machine's action and writes the result with
 * [CallLogDao.casReport]. The report message is never sent to the server; only the resulting status is synced through
 * [syncStatus] (`POST /calls/{id}/report-status`), best effort. It logs nothing and writes nothing to the Outbox (14.28).
 */
class SpamReporter(
    private val dao: CallLogDao,
    private val settings: SettingsStore,
    private val launcher: SmsIntentLauncher,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val ownNumber: () -> String? = { null },
    private val phrases: PhraseTables = PhraseTables.DEFAULT,
    private val newAttemptId: () -> String = { UUID.randomUUID().toString() },
    private val casRetries: Int = CAS_RETRIES,
    private val casRetryDelayMs: Long = CAS_RETRY_DELAY_MS,
    /** Sends the new status to `POST /calls/{id}/report-status`. Best effort: a failure never changes the local result. */
    private val syncStatus: suspend (entryId: String, status: ReportStatus) -> Unit = { _, _ -> },
) {

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Description chosen for the open confirmation, by attempt id. Held in memory only. */
    private val descriptions = ConcurrentHashMap<String, String>()

    private fun lockFor(entryId: String) = locks.getOrPut(entryId) { Mutex() }

    /** Starts a report. Settings and permission are read now, so a changed setting applies from this report (9.26). */
    suspend fun onReportSpam(entryId: String, repeatConfirmed: Boolean = false): ReportUiState =
        lockFor(entryId).withLock {
            val entry = dao.get(entryId) ?: return@withLock ReportUiState.Idle
            val s = settings.current()
            val flags = ReportFlowPolicy.flags(
                callerNumber = entry.callerNumber,
                silentSendSetting = false,
                permissionGranted = false,
                silentSmsEnabled = false,
            )
            val event = ReportEvent.ReportSpamTapped(
                atEpochMs = clock.millis(),
                zoneId = zone().id,
                newAttemptId = newAttemptId(),
                senderAvailable = flags.senderAvailable,
                silentSend = flags.silentSend,
                sendSmsGranted = flags.sendSmsGranted,
                repeatConfirmed = repeatConfirmed,
            )
            val applied = applyLocked(entryId, event) ?: return@withLock ReportUiState.Idle
            val (step, fresh) = applied
            when (val action = step.action) {
                ReportAction.ShowCannotReport -> {
                    sync(entryId, step.info.status)
                    ReportUiState.CannotReport
                }
                ReportAction.AskRepeatConfirmation ->
                    ReportUiState.AskRepeat(fresh.report.status, fresh.report.reportedAtEpochMs)
                is ReportAction.ShowForConfirmation -> {
                    val attemptId = step.info.attemptId ?: return@withLock ReportUiState.Idle
                    val description = BriefDescriptionGenerator.generate(fresh.intent, fresh.transcript, phrases)
                    descriptions[attemptId] = description
                    confirmState(fresh, attemptId, description, action.permissionNote, null)
                        ?: ReportUiState.Idle
                }
                else -> ReportUiState.Idle
            }
        }

    /** Applies a User edit of the Brief_Description. An invalid edit keeps the previous text (14.9). */
    suspend fun updateDescription(entryId: String, attemptId: String, text: String): ReportUiState =
        lockFor(entryId).withLock {
            val entry = dao.get(entryId) ?: return@withLock ReportUiState.Idle
            if (!awaiting(entry.report, attemptId)) return@withLock ReportUiState.Idle
            val previous = descriptions[attemptId]
                ?: BriefDescriptionGenerator.generate(entry.intent, entry.transcript, phrases)
            val s = settings.current()
            val protectedValues = buildSet {
                addAll(s.protectedTerms)
                s.mapLocation?.let { add(it) }
                s.address?.let { add(it) }
                s.addressHi?.let { add(it) }
                s.transferTarget?.let { add(it) }
                ownNumber()?.let { add(it) }
            }.filter { it.isNotBlank() }.toSet()
            when (val check = SpamReportBuilder.validateDescription(text, protectedValues)) {
                is DescriptionCheck.Valid -> {
                    descriptions[attemptId] = check.text
                    confirmState(entry, attemptId, check.text, false, null)
                }
                is DescriptionCheck.Invalid ->
                    confirmState(entry, attemptId, previous, false, check.reason)
            } ?: ReportUiState.Idle
        }

    /** The User confirmed the message: hand it to the messaging app (14.14). Never calls SmsManager (14.15). */
    suspend fun confirm(entryId: String, attemptId: String): ReportUiState =
        lockFor(entryId).withLock {
            val (step, entry) = applyLocked(entryId, ReportEvent.UserConfirmed(clock.millis(), attemptId))
                ?: return@withLock ReportUiState.Idle
            if (step.action != ReportAction.HandOff) return@withLock ReportUiState.Idle
            val message = messageFor(entry, attemptId)
                ?: return@withLock finishHandOff(entryId, attemptId, handedOff = false)
            val opened = try {
                launcher.openComposer(message)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            descriptions.remove(attemptId)
            finishHandOff(entryId, attemptId, handedOff = opened)
        }

    /** The User cancelled the confirmation; the status goes back to what it was (14.20). */
    suspend fun cancel(entryId: String, attemptId: String) {
        lockFor(entryId).withLock {
            applyLocked(entryId, ReportEvent.UserCancelled(clock.millis(), attemptId))
            descriptions.remove(attemptId)
        }
    }

    private suspend fun finishHandOff(entryId: String, attemptId: String, handedOff: Boolean): ReportUiState {
        val event = if (handedOff) ReportEvent.HandedOff(clock.millis(), attemptId)
        else ReportEvent.NoMessagingApp(clock.millis(), attemptId)
        val applied = applyLocked(entryId, event) ?: return ReportUiState.Idle
        sync(entryId, applied.first.info.status)
        return when (applied.first.action) {
            ReportAction.ShowNoMessagingApp -> ReportUiState.NoMessagingApp
            ReportAction.None -> if (handedOff) ReportUiState.HandedToMessagingApp else ReportUiState.Idle
            else -> ReportUiState.Idle
        }
    }

    private suspend fun sync(entryId: String, status: ReportStatus) {
        try {
            syncStatus(entryId, status)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Offline or rejected: the local status stays; the report itself does not depend on the server.
        }
    }

    private suspend fun messageFor(entry: CallLogEntry, attemptId: String): String? {
        val sender = SenderNumber.normalize(entry.callerNumber) ?: return null
        val description = descriptions[attemptId]
            ?: BriefDescriptionGenerator.generate(entry.intent, entry.transcript, phrases)
        val date = SpamReportBuilder.reportDate(entry.startedAtEpochMs, zone())
        return SpamReportBuilder.build(description, sender, date).text
    }

    private fun confirmState(
        entry: CallLogEntry,
        attemptId: String,
        description: String,
        permissionNote: Boolean,
        error: DescriptionError?,
    ): ReportUiState.Confirm? {
        val sender = SenderNumber.normalize(entry.callerNumber) ?: return null
        val date = SpamReportBuilder.reportDate(entry.startedAtEpochMs, zone())
        val message = SpamReportBuilder.build(description, sender, date)
        return ReportUiState.Confirm(
            entryId = entry.id,
            attemptId = attemptId,
            text = message.text,
            description = message.description,
            truncated = message.truncated,
            permissionNote = permissionNote,
            validationError = error,
        )
    }

    private fun awaiting(info: SpamReportInfo, attemptId: String): Boolean =
        info.status == ReportStatus.PREPARED && !info.dispatched && info.attemptId == attemptId

    /**
     * Loads the entry, reduces [event] and writes the result with a compare-and-set, retrying on a lost race
     * or a database error (3 tries 300 ms apart, well inside 2 s). Returns the step and the entry as loaded
     * before the write, or null when the entry is missing or every write failed. A step that changes nothing
     * writes nothing. Must be called with the entry lock held.
     */
    private suspend fun applyLocked(entryId: String, event: ReportEvent): Pair<ReportStep, CallLogEntry>? {
        for (attempt in 0 until casRetries) {
            val entry = dao.get(entryId) ?: return null
            val step = ReportStatusMachine.reduce(entry.report, event)
            if (step.info == entry.report) return step to entry
            val written = try {
                dao.casReport(entryId, entry.report.status, entry.report.attemptId, step.info)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                -1
            }
            if (written == 1) return step to entry
            if (attempt < casRetries - 1) delay(casRetryDelayMs)
        }
        return null
    }

    companion object {
        const val CAS_RETRIES = 3
        const val CAS_RETRY_DELAY_MS = 300L

        /** Wires the Android collaborators. */
        fun create(@Suppress("UNUSED_PARAMETER") context: Context, dao: CallLogDao, settings: SettingsStore, launcher: SmsIntentLauncher,
            syncStatus: suspend (String, ReportStatus) -> Unit = { _, _ -> },
        ): SpamReporter =
            SpamReporter(
                syncStatus = syncStatus,
                dao = dao,
                settings = settings,
                launcher = launcher,
                clock = Clock.systemUTC(),
                zone = { ZoneId.systemDefault() },
            )

    }
}