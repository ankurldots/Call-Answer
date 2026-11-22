package com.callanswer.app.ui.screens

import com.callanswer.core.delivery.DeliveryDirectionsRenderer
import com.callanswer.core.delivery.MissingLanguageWarning
import com.callanswer.core.delivery.StepListError
import com.callanswer.core.delivery.StepListResult
import com.callanswer.core.delivery.StepListValidator
import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Language
import com.callanswer.core.model.Settings

/** Editing helpers for the Delivery_Steps list (9.16 to 9.22). */
object DeliveryStepsDraft {
    /** Message shown for a rejected list; always states the allowed limits (9.20). */
    val LIMITS_MESSAGE: String = "Use 1 to ${StepListValidator.MAX_STEPS} steps. " +
        "Each step needs text in English or Hindi, at most ${StepListValidator.MAX_TEXT_LENGTH} characters per text."

    sealed interface Result {
        /** The list is valid. [warnings] name the steps that miss one language; the list is still saved (9.18). */
        data class Ok(val settings: Settings, val warnings: List<MissingLanguageWarning>) : Result
        data class Invalid(val errors: Set<StepListError>, val message: String = LIMITS_MESSAGE) : Result
    }

    fun newStep(id: String): DeliveryStep = DeliveryStep(id, "", "")

    fun trimmed(rows: List<DeliveryStep>): List<DeliveryStep> =
        rows.map { it.copy(textEn = it.textEn.trim(), textHi = it.textHi.trim()) }

    fun applyTo(base: Settings, rows: List<DeliveryStep>): Result {
        val steps = trimmed(rows)
        return when (val r = StepListValidator.validate(steps)) {
            is StepListResult.Rejected -> Result.Invalid(r.errors)
            is StepListResult.Accepted -> Result.Ok(base.copy(deliverySteps = steps), r.warnings)
        }
    }

    /** Returns a copy with the row at [index] moved by [delta] places; out-of-range moves change nothing. */
    fun <T> move(rows: List<T>, index: Int, delta: Int): List<T> {
        val to = index + delta
        if (index !in rows.indices || to !in rows.indices) return rows
        return rows.toMutableList().apply { add(to, removeAt(index)) }
    }

    /** The language of the text a step is missing, as a display name. */
    fun languageName(language: Language): String = when (language) {
        Language.EN -> "English"
        Language.HI -> "Hindi"
    }

    /** Spoken text of the whole Delivery_Directions in [language], steps only (the gate statement varies by time). */
    fun sampleText(rows: List<DeliveryStep>, language: Language, mapLocation: String?): String =
        DeliveryDirectionsRenderer.render(trimmed(rows), language, mapLocation, "", null)
            .drop(1)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}
