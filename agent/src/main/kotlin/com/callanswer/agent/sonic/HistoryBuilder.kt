package com.callanswer.agent.sonic

import com.callanswer.core.model.Language
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.Utterance
import com.callanswer.core.stalling.PiiGuard

/** Server-side counters that must survive a session renewal (design: "Sonic session management"). */
data class ServerState(
    val intent: String? = null,
    val language: Language? = null,
    val directionsRepetitions: Int = 0,
    val stallingStartedAtMs: Long? = null,
    val takeoverState: String = "NONE",
)

object HistoryBuilder {
    const val MAX_UTTERANCES = 40
    const val STATE_MARKER = "[[STATE]]"

    /**
     * History for a replacement session: one state line, then the last [MAX_UTTERANCES] spoken utterances
     * (Caller as `USER`, Agent as `ASSISTANT`). In Stalling_Mode ([guard] not null) each text passes through
     * [PiiGuard.sanitizePrompt]. Takeover speakers (`USER`, `LIVE`) and blank texts are skipped.
     */
    fun build(utterances: List<Utterance>, state: ServerState, guard: PiiGuard? = null): List<HistoryMessage> {
        val messages = utterances.mapNotNull { u ->
            val role = when (u.speaker) {
                Speaker.CALLER -> "USER"
                Speaker.AGENT -> "ASSISTANT"
                else -> return@mapNotNull null
            }
            val text = u.text.trim()
            if (text.isEmpty()) return@mapNotNull null
            HistoryMessage(role, guard?.sanitizePrompt(text) ?: text)
        }.takeLast(MAX_UTTERANCES)
        return listOf(HistoryMessage("USER", stateLine(state))) + messages
    }

    fun stateLine(s: ServerState): String =
        "$STATE_MARKER intent=${s.intent ?: "NONE"}; language=${s.language?.name?.lowercase() ?: "unknown"}; " +
            "directions_repetitions=${s.directionsRepetitions}; stalling_since_ms=${s.stallingStartedAtMs ?: "none"}; " +
            "takeover=${s.takeoverState}"
}
