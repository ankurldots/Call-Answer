package com.callanswer.app.telecom

import com.callanswer.app.telecom.StartThenAnswer.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StartThenAnswerTest {
    private fun run(start: Boolean, running: Boolean, answers: Boolean): Triple<Outcome, Boolean, Boolean> {
        var answered = false
        var notStarted = false
        val outcome = runBlocking {
            StartThenAnswer(
                start = { start },
                awaitRunning = { running },
                answer = { answered = true; answers },
                onNotStarted = { notStarted = true },
            ).run()
        }
        return Triple(outcome, answered, notStarted)
    }

    @Test fun answersOnlyAfterServiceRuns() {
        assertEquals(Triple(Outcome.ANSWERED, true, false), run(start = true, running = true, answers = true))
    }

    @Test fun startRefusedNeverAnswers() {
        assertEquals(Triple(Outcome.NOT_STARTED, false, true), run(start = false, running = true, answers = true))
    }

    @Test fun noConfirmationNeverAnswers() {
        assertEquals(Triple(Outcome.NOT_STARTED, false, true), run(start = true, running = false, answers = true))
    }

    @Test fun answerFailureIsReportedWhenServiceRuns() {
        assertEquals(Triple(Outcome.ANSWER_FAILED, true, false), run(start = true, running = true, answers = false))
    }
}
