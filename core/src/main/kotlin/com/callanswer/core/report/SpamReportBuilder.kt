package com.callanswer.core.report

import com.callanswer.core.intent.PhraseTables
import com.callanswer.core.intent.SolicitationTopic
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.Utterance
import com.callanswer.core.stalling.PiiGuard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

object TraiReport {
    const val NUMBER = "1909"
    const val MAX_LENGTH = 160
    const val SEPARATOR = ", "
}

object BriefDescriptionGenerator {
    const val LOAN = "Unsolicited loan offer call"
    const val PROPERTY = "Unsolicited property sale call"
    const val SALES = "Unsolicited sales call"
    const val GENERIC = "Unsolicited call"

    /** Returns one of the four constants; transcript text is only read for matching (14.6 to 14.8). */
    fun generate(intent: IntentCategory, transcript: List<Utterance>, phrases: PhraseTables): String {
        if (intent != IntentCategory.SOLICITATION) return GENERIC
        val texts = transcript
            .filter { it.speaker == Speaker.CALLER || it.speaker == Speaker.LIVE }
            .map { it.text }
        return when {
            texts.any { phrases.isTopic(SolicitationTopic.LOAN, it) } -> LOAN
            texts.any { phrases.isTopic(SolicitationTopic.PROPERTY, it) } -> PROPERTY
            else -> SALES
        }
    }
}

sealed interface DescriptionCheck {
    data class Valid(val text: String) : DescriptionCheck
    data class Invalid(val reason: DescriptionError) : DescriptionCheck
}

enum class DescriptionError { EMPTY, NON_ASCII_OR_CONTROL, CONTAINS_COMMA, CONTAINS_PROTECTED_VALUE }

object SenderNumber {
    private val FORMATTING = Regex("[ \\-().]")
    private val VALID = Regex("^\\+?\\d{7,15}$")

    /** Null means the number is unavailable (14.11, 14.13). */
    fun normalize(recorded: String?): String? {
        if (recorded == null) return null
        val cleaned = recorded.trim().replace(FORMATTING, "")
        return cleaned.takeIf { VALID.matches(it) }
    }

    internal fun isValid(s: String): Boolean = VALID.matches(s)
}

data class SpamReportMessage(
    val text: String,
    val description: String,
    val sender: String,
    val date: String,
    val truncated: Boolean,
)

data class ParsedSpamReport(val description: String, val sender: String, val date: String)

object SpamReportBuilder {
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ROOT)
    private val DATE_SHAPE = Regex("^\\d{2}/\\d{2}/\\d{2}$")
    private val STRICT_PARSE = DateTimeFormatter.ofPattern("dd/MM/uu", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun validateDescription(text: String, protectedValues: Set<String>): DescriptionCheck {
        val t = text.trim(' ')
        if (t.isEmpty()) return DescriptionCheck.Invalid(DescriptionError.EMPTY)
        if (t.any { it.code !in 0x20..0x7E }) return DescriptionCheck.Invalid(DescriptionError.NON_ASCII_OR_CONTROL)
        if (t.contains(',')) return DescriptionCheck.Invalid(DescriptionError.CONTAINS_COMMA)
        if (PiiGuard(protectedValues).containsProtected(t)) {
            return DescriptionCheck.Invalid(DescriptionError.CONTAINS_PROTECTED_VALUE)
        }
        return DescriptionCheck.Valid(t)
    }

    fun reportDate(callStartEpochMs: Long, deviceZone: ZoneId): String =
        DATE_FORMAT.format(Instant.ofEpochMilli(callStartEpochMs).atZone(deviceZone))

    fun build(description: String, sender: String, date: String): SpamReportMessage {
        val limit = TraiReport.MAX_LENGTH - (2 * TraiReport.SEPARATOR.length + sender.length + date.length)
        val truncated = description.length > limit
        val desc = if (truncated) description.take(limit).trimEnd(' ') else description
        val text = desc + TraiReport.SEPARATOR + sender + TraiReport.SEPARATOR + date
        return SpamReportMessage(text, desc, sender, date, truncated)
    }

    fun parse(message: String): ParsedSpamReport? {
        val parts = message.split(",")
        if (parts.size != 3) return null
        if (!parts[1].startsWith(" ") || !parts[2].startsWith(" ")) return null
        val description = parts[0]
        val sender = parts[1].substring(1)
        val date = parts[2].substring(1)
        if (description.isEmpty() || description.any { it.code !in 0x20..0x7E }) return null
        if (!SenderNumber.isValid(sender)) return null
        if (!DATE_SHAPE.matches(date)) return null
        try {
            LocalDate.parse(date, STRICT_PARSE)
        } catch (e: java.time.DateTimeException) {
            return null
        }
        return ParsedSpamReport(description, sender, date)
    }
}
