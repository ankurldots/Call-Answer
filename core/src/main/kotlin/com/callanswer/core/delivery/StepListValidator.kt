package com.callanswer.core.delivery

import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Language

enum class StepListError { EMPTY, TOO_MANY_STEPS, STEP_WITHOUT_TEXT, TEXT_TOO_LONG }

/** A step with exactly one blank text. */
data class MissingLanguageWarning(val stepId: String, val missing: Language)

sealed class StepListResult {
    data class Accepted(val warnings: List<MissingLanguageWarning>) : StepListResult()
    data class Rejected(val errors: Set<StepListError>) : StepListResult()

    val isAccepted: Boolean get() = this is Accepted
}

object StepListValidator {
    const val MIN_STEPS = 1
    const val MAX_STEPS = 10
    const val MAX_TEXT_LENGTH = 200

    fun validate(steps: List<DeliveryStep>): StepListResult {
        val errors = buildSet {
            if (steps.size < MIN_STEPS) add(StepListError.EMPTY)
            if (steps.size > MAX_STEPS) add(StepListError.TOO_MANY_STEPS)
            if (steps.any { it.textEn.isBlank() && it.textHi.isBlank() }) add(StepListError.STEP_WITHOUT_TEXT)
            if (steps.any { it.textEn.length > MAX_TEXT_LENGTH || it.textHi.length > MAX_TEXT_LENGTH }) {
                add(StepListError.TEXT_TOO_LONG)
            }
        }
        if (errors.isNotEmpty()) return StepListResult.Rejected(errors)
        val warnings = steps.mapNotNull {
            when {
                it.textEn.isBlank() -> MissingLanguageWarning(it.id, Language.EN)
                it.textHi.isBlank() -> MissingLanguageWarning(it.id, Language.HI)
                else -> null
            }
        }
        return StepListResult.Accepted(warnings)
    }
}
