package com.callanswer.core.stalling

import com.callanswer.core.model.Language
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.Utterance

data class PromptMessage(val role: Role, val content: String) {
    enum class Role { USER, ASSISTANT }
}

data class StallPrompt(val system: String, val messages: List<PromptMessage>)

/**
 * Builds the cloud prompt for a stalling reply (Requirements 5.4, 5.7, 10.7).
 *
 * It takes only Caller text, the language and recent turns. It has no `Settings` or `Gate` parameter,
 * so real configuration cannot enter a prompt. All text is passed through [PiiGuard.sanitizePrompt].
 */
class PromptBuilder(private val guard: PiiGuard, private val maxTurns: Int = DEFAULT_MAX_TURNS) {

    fun build(latestCallerText: String, language: Language, recentTurns: List<Utterance>): StallPrompt {
        val history = recentTurns
            .filter { it.speaker == Speaker.CALLER || it.speaker == Speaker.AGENT }
            .takeLast(maxTurns)
            .map {
                PromptMessage(
                    if (it.speaker == Speaker.CALLER) PromptMessage.Role.USER else PromptMessage.Role.ASSISTANT,
                    guard.sanitizePrompt(it.text),
                )
            }
        val latest = PromptMessage(PromptMessage.Role.USER, guard.sanitizePrompt(latestCallerText))
        return StallPrompt(systemPrompt(language), history + latest)
    }

    companion object {
        const val DEFAULT_MAX_TURNS = 6

        fun systemPrompt(language: Language): String {
            val lang = if (language == Language.HI) "Hindi" else "English"
            return "You are a person answering a phone call from an unknown caller. " +
                "Invent a fictional household and fictional details. " +
                "Never reveal any real personal data, phone number, address, name or gate information. " +
                "Reply only in $lang, in at most two sentences, and end with exactly one question."
        }
    }
}
