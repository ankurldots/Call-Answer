package com.callanswer.app.service

import android.content.Context
import android.util.Log
import com.callanswer.app.data.CallAnswerDatabase
import com.callanswer.app.data.CallLogDao
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.recording.RecordingLinker
import com.callanswer.core.engine.CallSession
import com.callanswer.core.engine.ConversationState
import com.callanswer.core.engine.EndedBy
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.CallLogEntryBuilder
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.LogNoteKind
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.TransferOutcome
import com.callanswer.core.model.VoiceSubstitution
import kotlinx.coroutines.CancellationException

/** Derives the logged end reason and outcome from the final conversation state (10.1, 5.9). */
object CallOutcomeResolver {

    /**
     * [explicit] is the reason of the `EndCall` effect the engine emitted, if any. Otherwise the call was ended from
     * outside: [by] says who hung up (a transfer in progress gives TRANSFERRED_CALL_ENDED).
     */
    fun endReason(state: ConversationState, explicit: EndReason?, by: EndedBy?): EndReason {
        if (explicit != null) return explicit
        if (state.interruption != null) return EndReason.AUDIO_INTERRUPTED
        return when {
            by == EndedBy.USER -> EndReason.USER_ENDED
            state.transfer?.outcome == TransferOutcome.MERGED -> EndReason.TRANSFERRED_CALL_ENDED
            else -> EndReason.CALLER_HUNG_UP
        }
    }

    fun outcome(state: ConversationState, reason: EndReason, notes: List<LogNoteKind>): Outcome {
        if (state.interruption != null || reason == EndReason.AUDIO_INTERRUPTED) return Outcome.INTERRUPTED
        val transferOutcome = state.transfer?.outcome
        if (state.takeovers.isNotEmpty() || transferOutcome == TransferOutcome.TAKEN_OVER_BY_USER) return Outcome.TAKEN_OVER
        if (transferOutcome == TransferOutcome.MERGED) return Outcome.TRANSFERRED
        if (LogNoteKind.TRANSFER_FAILED in notes) return Outcome.TRANSFER_FAILED
        return when (reason) {
            EndReason.DELIVERY_DONE, EndReason.REPEATS_EXHAUSTED -> Outcome.DELIVERY_DIRECTIONS_GIVEN
            EndReason.USER_ENDED -> Outcome.USER_ENDED
            EndReason.CALLER_SILENCE -> Outcome.CALLER_SILENT
            EndReason.UNKNOWN_AFTER_CLARIFY -> Outcome.UNKNOWN_CALLBACK
            EndReason.MAX_DURATION -> Outcome.STALLED
            EndReason.RESPONSE_FAILURE -> Outcome.ERROR
            EndReason.TRANSFERRED_CALL_ENDED -> Outcome.TRANSFERRED
            EndReason.CALLER_HUNG_UP -> when {
                state.directionsDone -> Outcome.DELIVERY_DIRECTIONS_GIVEN
                state.stallStartMs != null -> Outcome.STALLED
                else -> Outcome.CALLER_HUNG_UP
            }
            EndReason.AUDIO_INTERRUPTED -> Outcome.INTERRUPTED
        }
    }
}

/** Everything the call-end finalization needs from the call. */
class FinishedCall(
    val session: CallSession,
    val state: ConversationState,
    val endedAtMs: Long,
    val endReason: EndReason,
    val notes: List<LogNoteKind>,
    val voiceSubstitutions: List<VoiceSubstitution>,
)

/**
 * Call-end finalization (Requirements 5.9, 6.3, 6.6, 6.7, 10.1, 10.3, 10.12, 12.1, 12.7, 13.18, 14.2, 14.3):
 * saves the Call_Log entry (retried by [CallLogDao.save]), schedules the recording search, and posts the
 * unknown-call, silent-caller and post-call spam notifications. Never throws.
 */
class CallFinalizer(
    private val context: Context,
    private val notifications: NotificationCenter = NotificationCenter(context),
) {
    suspend fun finalize(call: FinishedCall) {
        try {
            run(call)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "finalization failed", t)
            notifications.callNotLogged(call.session.callerNumber.orEmpty())
        }
    }

    private suspend fun run(call: FinishedCall) {
        val app = context.applicationContext
        val number = call.session.callerNumber
        val folderSelected = runCatching {
            !SettingsStore.create(app).current().recordingFolderUri.isNullOrBlank()
        }.getOrDefault(false)
        val notes = call.notes.distinct()
        val entry = CallLogEntryBuilder.build(
            session = call.session,
            state = call.state,
            endedAtMs = call.endedAtMs,
            outcome = CallOutcomeResolver.outcome(call.state, call.endReason, notes),
            endReason = call.endReason,
            notes = notes,
            voiceSubstitutions = call.voiceSubstitutions.distinct(),
            folderSelected = folderSelected,
        )
        val saved = save(app, entry, number)
        // Recording: the entry already says NO_FOLDER_SELECTED without a folder (12.7); otherwise search (12.1).
        if (saved && folderSelected) runCatching { RecordingLinker.schedule(app, entry.id) }
        postNotifications(call, entry, saved)
    }

    private suspend fun save(app: Context, entry: CallLogEntry, number: String?): Boolean {
        val db = CallAnswerDatabase.create(app)
        return try {
            db.callLogDao().save(entry, onNotLogged = { _, _ -> notifications.callNotLogged(number.orEmpty()) })
        } finally {
            runCatching { db.close() }
        }
    }

    private fun postNotifications(call: FinishedCall, entry: CallLogEntry, saved: Boolean) {
        val number = call.session.callerNumber
        runCatching {
            when (call.endReason) {
                // 6.3, 6.6: the transcript, and an indication when the number is unavailable.
                EndReason.UNKNOWN_AFTER_CLARIFY -> notifications.unknownCallWithTranscript(
                    number ?: context.getString(com.callanswer.app.R.string.notif_number_unavailable),
                    transcriptText(call.state),
                )
                // 6.7
                EndReason.CALLER_SILENCE -> notifications.silentCaller(number.orEmpty())
                else -> Unit
            }
        }
        // 14.2: SOLICITATION only. The notification sends nothing (14.3). It needs the saved entry.
        if (saved && call.state.intent == IntentCategory.SOLICITATION) {
            runCatching { notifications.postCallSpam(number.orEmpty(), entry.id) }
        }
    }

    private fun transcriptText(state: ConversationState): String =
        state.transcript.joinToString("\n") { "${label(it.speaker)}: ${it.text}" }

    private fun label(s: Speaker): String = when (s) {
        Speaker.CALLER -> "Caller"
        Speaker.AGENT -> "Agent"
        Speaker.USER -> "You"
        Speaker.LIVE -> "Live"
    }

    private companion object {
        const val TAG = "CallFinalizer"
    }
}
