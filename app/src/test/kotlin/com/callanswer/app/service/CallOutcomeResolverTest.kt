package com.callanswer.app.service

import com.callanswer.core.engine.ConversationState
import com.callanswer.core.engine.EndedBy
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.LogNoteKind
import com.callanswer.core.model.Outcome
import org.junit.Test
import org.junit.Assert.assertEquals

class CallOutcomeResolverTest {
    private val none = emptyList<LogNoteKind>()

    @Test fun explicitReasonWins() {
        assertEquals(EndReason.CALLER_SILENCE, CallOutcomeResolver.endReason(ConversationState(), EndReason.CALLER_SILENCE, EndedBy.CALLER))
    }

    @Test fun callerHangUpWithoutReason() {
        assertEquals(EndReason.CALLER_HUNG_UP, CallOutcomeResolver.endReason(ConversationState(), null, EndedBy.CALLER))
        assertEquals(EndReason.USER_ENDED, CallOutcomeResolver.endReason(ConversationState(), null, EndedBy.USER))
    }

    @Test fun hangUpWhileStallingIsStalled() {
        val s = ConversationState(stallStartMs = 1000)
        assertEquals(Outcome.STALLED, CallOutcomeResolver.outcome(s, EndReason.CALLER_HUNG_UP, none))
    }

    @Test fun hangUpAfterDirectionsIsDelivered() {
        val s = ConversationState(directionsDone = true)
        assertEquals(Outcome.DELIVERY_DIRECTIONS_GIVEN, CallOutcomeResolver.outcome(s, EndReason.CALLER_HUNG_UP, none))
    }

    @Test fun silenceAndUnknownMapToTheirOutcomes() {
        assertEquals(Outcome.CALLER_SILENT, CallOutcomeResolver.outcome(ConversationState(), EndReason.CALLER_SILENCE, none))
        assertEquals(Outcome.UNKNOWN_CALLBACK, CallOutcomeResolver.outcome(ConversationState(), EndReason.UNKNOWN_AFTER_CLARIFY, none))
    }

    @Test fun failedTransferNoteGivesTransferFailed() {
        assertEquals(
            Outcome.TRANSFER_FAILED,
            CallOutcomeResolver.outcome(ConversationState(), EndReason.CALLER_HUNG_UP, listOf(LogNoteKind.TRANSFER_FAILED)),
        )
    }
    @Test
    fun abusiveCallerOutcome() {
        assertEquals(Outcome.ABUSIVE_CALLER, CallOutcomeResolver.outcome(ConversationState(), EndReason.ABUSIVE_LANGUAGE, emptyList()))
    }
}
