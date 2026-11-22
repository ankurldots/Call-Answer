package com.callanswer.core.settings

import com.callanswer.core.delivery.StepListResult
import com.callanswer.core.delivery.StepListValidator
import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Gate
import com.callanswer.core.model.LocalTimeHm

/** A rejected settings field. Each message names the allowed format or range. */
sealed class SettingsError(val message: String) {
    data object InvalidTransferTarget :
        SettingsError("Enter 7 to 15 digits, with an optional leading +")

    data object InvalidLocationUrl :
        SettingsError("Enter a link that starts with http:// or https://, without spaces")
    data object InvalidMaxStalling :
        SettingsError("Enter a whole number of seconds from 30 to 600")

    data object InvalidNightWindowTime :
        SettingsError("Enter times in 24-hour HH:MM format, from 00:00 to 23:59")

    data object NightWindowStartEqualsEnd :
        SettingsError("Night window start and end must be different times")

    data class InvalidGateField(val field: GateField) :
        SettingsError("Gate ${field.label} must be 1 to 100 characters")
}

enum class GateField(val label: String) { NUMBER("number"), LOCATION("location"), LANDMARK("landmark") }

sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val errors: List<SettingsError>) : ValidationResult()

    val isValid: Boolean get() = this is Valid
}

object SettingsValidator {
    private val TRANSFER_TARGET = Regex("""\+?[0-9]{7,15}""")
    private val WHOLE_NUMBER = Regex("""[0-9]{1,9}""")
    private val HM_24H = Regex("""([01][0-9]|2[0-3]):[0-5][0-9]""")
    private const val MAX_STALLING_MIN = 30
    private const val MAX_STALLING_MAX = 600
    private const val GATE_FIELD_MAX = 100

    private fun invalid(vararg e: SettingsError) = ValidationResult.Invalid(e.toList())

    fun validateTransferTarget(s: String): ValidationResult =
        if (TRANSFER_TARGET.matches(s)) ValidationResult.Valid else invalid(SettingsError.InvalidTransferTarget)

    private val LOCATION_URL = Regex("""(?i)https?://\S+""")
    fun validateLocationUrl(s: String): ValidationResult =
        if (LOCATION_URL.matches(s)) ValidationResult.Valid else invalid(SettingsError.InvalidLocationUrl)
    fun validateMaxStalling(s: String): ValidationResult {
        val n = if (WHOLE_NUMBER.matches(s)) s.toInt() else null
        return if (n != null && n in MAX_STALLING_MIN..MAX_STALLING_MAX) {
            ValidationResult.Valid
        } else {
            invalid(SettingsError.InvalidMaxStalling)
        }
    }

    fun validateNightWindow(start: String, end: String): ValidationResult {
        if (!HM_24H.matches(start) || !HM_24H.matches(end)) return invalid(SettingsError.InvalidNightWindowTime)
        val a = LocalTimeHm.parse(start)
        val b = LocalTimeHm.parse(end)
        if (a == null || b == null) return invalid(SettingsError.InvalidNightWindowTime)
        return if (a == b) invalid(SettingsError.NightWindowStartEqualsEnd) else ValidationResult.Valid
    }

    fun validateSteps(steps: List<DeliveryStep>): StepListResult = StepListValidator.validate(steps)

    fun validateGate(g: Gate): ValidationResult {
        val errors = buildList {
            if (g.number.trim().length !in 1..GATE_FIELD_MAX) add(SettingsError.InvalidGateField(GateField.NUMBER))
            if (g.location.trim().length !in 1..GATE_FIELD_MAX) add(SettingsError.InvalidGateField(GateField.LOCATION))
            if (g.landmark.trim().length !in 1..GATE_FIELD_MAX) add(SettingsError.InvalidGateField(GateField.LANDMARK))
        }
        return if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
    }
}
