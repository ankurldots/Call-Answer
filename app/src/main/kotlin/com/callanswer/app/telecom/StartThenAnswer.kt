package com.callanswer.app.telecom

/**
 * The rule "never answer unless the call service is running in the foreground". The call is answered only after
 * [start] was accepted by the system and [awaitRunning] confirmed the service entered the foreground. Otherwise
 * [onNotStarted] runs and the call keeps ringing, so the caller never hears silence from an unanswered Agent.
 */
class StartThenAnswer(
    private val start: () -> Boolean,
    private val awaitRunning: suspend () -> Boolean,
    private val answer: suspend () -> Boolean,
    private val onNotStarted: () -> Unit,
) {
    enum class Outcome { NOT_STARTED, ANSWERED, ANSWER_FAILED }

    suspend fun run(): Outcome {
        if (!start() || !awaitRunning()) {
            onNotStarted()
            return Outcome.NOT_STARTED
        }
        return if (answer()) Outcome.ANSWERED else Outcome.ANSWER_FAILED
    }
}
