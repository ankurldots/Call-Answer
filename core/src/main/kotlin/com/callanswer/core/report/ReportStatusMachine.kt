package com.callanswer.core.report

import com.callanswer.core.model.ReportStamp
import com.callanswer.core.model.ReportStatus
import com.callanswer.core.model.SpamReportInfo

sealed interface ReportEvent {
    val atEpochMs: Long

    data class ReportSpamTapped(
        override val atEpochMs: Long,
        val zoneId: String,
        val newAttemptId: String,
        val senderAvailable: Boolean,
        val silentSend: Boolean,
        val sendSmsGranted: Boolean,
        /** True only after the User confirms a repeat report (14.26). */
        val repeatConfirmed: Boolean = false,
    ) : ReportEvent

    data class UserConfirmed(override val atEpochMs: Long, val attemptId: String) : ReportEvent
    data class UserCancelled(override val atEpochMs: Long, val attemptId: String) : ReportEvent
    data class HandedOff(override val atEpochMs: Long, val attemptId: String) : ReportEvent
    data class NoMessagingApp(override val atEpochMs: Long, val attemptId: String) : ReportEvent
    data class NoSignal(override val atEpochMs: Long, val attemptId: String) : ReportEvent
    data class SilentSendResult(override val atEpochMs: Long, val attemptId: String, val success: Boolean) : ReportEvent

    /** No send result 60 s after the silent send. */
    data class SilentSendTimeout(override val atEpochMs: Long, val attemptId: String) : ReportEvent
}

sealed interface ReportAction {
    data object None : ReportAction
    data object AskRepeatConfirmation : ReportAction
    data object ShowCannotReport : ReportAction

    /** [permissionNote] is true when silent send is on but SEND_SMS is not granted (14.17). */
    data class ShowForConfirmation(val permissionNote: Boolean) : ReportAction
    data object SendSilently : ReportAction
    data object HandOff : ReportAction
    data object ShowNoMessagingApp : ReportAction
    data object OfferRetry : ReportAction
}

data class ReportStep(val info: SpamReportInfo, val action: ReportAction)

/** Pure Report_Status transition function (Requirement 14). */
object ReportStatusMachine {

    fun reduce(info: SpamReportInfo, event: ReportEvent): ReportStep = when (event) {
        is ReportEvent.ReportSpamTapped -> onTapped(info, event)
        is ReportEvent.UserConfirmed ->
            if (awaitingConfirmation(info, event.attemptId)) {
                ReportStep(info.copy(dispatched = true), ReportAction.HandOff)
            } else unchanged(info)
        is ReportEvent.UserCancelled ->
            if (awaitingConfirmation(info, event.attemptId)) cancel(info) else unchanged(info)
        is ReportEvent.HandedOff ->
            dispatched(info, event.attemptId)?.let {
                ReportStep(stamp(it, ReportStatus.HANDED_TO_MESSAGING_APP, event.atEpochMs, zoneOf(it)), ReportAction.None)
            } ?: unchanged(info)
        is ReportEvent.NoMessagingApp ->
            dispatched(info, event.attemptId)?.let {
                ReportStep(stamp(it, ReportStatus.FAILED, event.atEpochMs, zoneOf(it)), ReportAction.ShowNoMessagingApp)
            } ?: unchanged(info)
        is ReportEvent.NoSignal ->
            dispatched(info, event.attemptId)?.let {
                ReportStep(stamp(it, ReportStatus.FAILED, event.atEpochMs, zoneOf(it)), ReportAction.OfferRetry)
            } ?: unchanged(info)
        is ReportEvent.SilentSendResult ->
            dispatched(info, event.attemptId)?.let {
                if (event.success) {
                    ReportStep(stamp(it, ReportStatus.SENT, event.atEpochMs, zoneOf(it)), ReportAction.None)
                } else {
                    ReportStep(stamp(it, ReportStatus.FAILED, event.atEpochMs, zoneOf(it)), ReportAction.OfferRetry)
                }
            } ?: unchanged(info)
        is ReportEvent.SilentSendTimeout ->
            dispatched(info, event.attemptId)?.let {
                ReportStep(stamp(it, ReportStatus.FAILED, event.atEpochMs, zoneOf(it)), ReportAction.OfferRetry)
            } ?: unchanged(info)
    }

    private fun onTapped(info: SpamReportInfo, e: ReportEvent.ReportSpamTapped): ReportStep {
        if (!e.senderAvailable) {
            val next = SpamReportInfo(
                status = ReportStatus.CANNOT_REPORT_NUMBER_UNAVAILABLE,
                reportedAtEpochMs = e.atEpochMs,
                reportedZoneId = e.zoneId,
                attemptId = null,
                dispatched = false,
                pendingRestore = null,
            )
            return ReportStep(next, ReportAction.ShowCannotReport)
        }
        val asksRepeat = info.status == ReportStatus.PREPARED ||
            info.status == ReportStatus.HANDED_TO_MESSAGING_APP ||
            info.status == ReportStatus.SENT
        if (asksRepeat && !e.repeatConfirmed) return ReportStep(info, ReportAction.AskRepeatConfirmation)

        return if (e.silentSend && e.sendSmsGranted) {
            ReportStep(
                SpamReportInfo(
                    status = ReportStatus.PREPARED,
                    reportedAtEpochMs = e.atEpochMs,
                    reportedZoneId = e.zoneId,
                    attemptId = e.newAttemptId,
                    dispatched = true,
                    pendingRestore = null,
                ),
                ReportAction.SendSilently,
            )
        } else {
            ReportStep(
                SpamReportInfo(
                    status = ReportStatus.PREPARED,
                    reportedAtEpochMs = e.atEpochMs,
                    reportedZoneId = e.zoneId,
                    attemptId = e.newAttemptId,
                    dispatched = false,
                    pendingRestore = ReportStamp(info.status, info.reportedAtEpochMs, info.reportedZoneId),
                ),
                ReportAction.ShowForConfirmation(permissionNote = e.silentSend && !e.sendSmsGranted),
            )
        }
    }

    private fun cancel(info: SpamReportInfo): ReportStep {
        val prev = info.pendingRestore
            ?: ReportStamp(ReportStatus.NOT_REPORTED, null, null)
        val restored = if (prev.status == ReportStatus.NOT_REPORTED) {
            SpamReportInfo()
        } else {
            SpamReportInfo(status = prev.status, reportedAtEpochMs = prev.atEpochMs, reportedZoneId = prev.zoneId)
        }
        return ReportStep(restored, ReportAction.None)
    }

    private fun awaitingConfirmation(info: SpamReportInfo, attemptId: String): Boolean =
        info.status == ReportStatus.PREPARED && !info.dispatched && info.attemptId == attemptId

    private fun dispatched(info: SpamReportInfo, attemptId: String): SpamReportInfo? =
        if (info.status == ReportStatus.PREPARED && info.dispatched && info.attemptId == attemptId) info else null

    private fun zoneOf(info: SpamReportInfo): String? = info.reportedZoneId

    /** Moves to [status], stamping time and zone, and ends the attempt's pending state. */
    private fun stamp(info: SpamReportInfo, status: ReportStatus, atEpochMs: Long, zoneId: String?): SpamReportInfo =
        info.copy(
            status = status,
            reportedAtEpochMs = atEpochMs,
            reportedZoneId = zoneId,
            pendingRestore = null,
        )

    private fun unchanged(info: SpamReportInfo) = ReportStep(info, ReportAction.None)
}
