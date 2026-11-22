package com.callanswer.app.ui.screens

import com.callanswer.core.model.Gate
import com.callanswer.core.model.GateConfig
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.model.NightWindow
import com.callanswer.core.model.QuickReply
import com.callanswer.core.model.Settings
import com.callanswer.core.settings.GateField
import com.callanswer.core.settings.SettingsError
import com.callanswer.core.settings.SettingsValidator
import com.callanswer.core.settings.ValidationResult

/** Form fields that can show an inline validation message. */
enum class SettingsField {
    LOCATION_URL, TRANSFER_TARGET, MAX_STALLING,
    GATE1_NUMBER, GATE1_LOCATION, GATE1_LANDMARK,
    GATE3_NUMBER, GATE3_LOCATION, GATE3_LANDMARK,
    NIGHT_START, NIGHT_END,
    QUICK_REPLIES,
}

/** Either a candidate [Settings] ready for `SettingsStore.update`, or per-field messages. */
sealed class DraftResult {
    data class Ok(val settings: Settings) : DraftResult()
    data class Invalid(val errors: Map<SettingsField, String>) : DraftResult()
}

data class GeneralDraft(
    val mapLocation: String,
    val address: String,
    val addressHi: String,
    val transferTarget: String,
    val maxStalling: String,
    val protectedTerms: String,
) {
    companion object {
        fun from(s: Settings) = GeneralDraft(
            mapLocation = s.mapLocation.orEmpty(),
            address = s.address.orEmpty(),
            addressHi = s.addressHi.orEmpty(),
            transferTarget = s.transferTarget.orEmpty(),
            maxStalling = s.maxStallingSeconds.toString(),
            protectedTerms = s.protectedTerms.joinToString("\n"),
        )
    }

    /** A blank Location URL, Address or Transfer_Target clears the value. */
    fun applyTo(base: Settings): DraftResult {
        val errors = mutableMapOf<SettingsField, String>()
        val url = mapLocation.trim()
        if (url.isNotEmpty()) {
            (SettingsValidator.validateLocationUrl(url) as? ValidationResult.Invalid)
                ?.let { errors[SettingsField.LOCATION_URL] = it.errors.first().message }
        }
        val target = transferTarget.trim()
        if (target.isNotEmpty()) {
            (SettingsValidator.validateTransferTarget(target) as? ValidationResult.Invalid)
                ?.let { errors[SettingsField.TRANSFER_TARGET] = it.errors.first().message }
        }
        val stalling = maxStalling.trim()
        (SettingsValidator.validateMaxStalling(stalling) as? ValidationResult.Invalid)
            ?.let { errors[SettingsField.MAX_STALLING] = it.errors.first().message }
        if (errors.isNotEmpty()) return DraftResult.Invalid(errors)
        return DraftResult.Ok(
            base.copy(
                mapLocation = url.ifEmpty { null },
                address = address.trim().ifEmpty { null },
                addressHi = addressHi.trim().ifEmpty { null },
                transferTarget = target.ifEmpty { null },
                maxStallingSeconds = stalling.toInt(),
                protectedTerms = parseTerms(protectedTerms),
            ),
        )
    }

    private fun parseTerms(text: String): List<String> =
        text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

data class GatesDraft(
    val gate1: Triple<String, String, String>,
    val gate3: Triple<String, String, String>,
    val nightStart: String,
    val nightEnd: String,
) {
    companion object {
        fun from(s: Settings): GatesDraft {
            val g = s.gates
            return GatesDraft(
                Triple(g.gate1.number, g.gate1.location, g.gate1.landmark),
                Triple(g.gate3.number, g.gate3.location, g.gate3.landmark),
                g.nightWindow.start.toString(),
                g.nightWindow.end.toString(),
            )
        }
    }

    fun applyTo(base: Settings): DraftResult {
        val errors = mutableMapOf<SettingsField, String>()
        val g1 = Gate(gate1.first.trim(), gate1.second.trim(), gate1.third.trim())
        val g3 = Gate(gate3.first.trim(), gate3.second.trim(), gate3.third.trim())
        gateErrors(g1, SettingsField.GATE1_NUMBER, SettingsField.GATE1_LOCATION, SettingsField.GATE1_LANDMARK, errors)
        gateErrors(g3, SettingsField.GATE3_NUMBER, SettingsField.GATE3_LOCATION, SettingsField.GATE3_LANDMARK, errors)

        val start = nightStart.trim()
        val end = nightEnd.trim()
        (SettingsValidator.validateNightWindow(start, end) as? ValidationResult.Invalid)?.errors?.forEach { e ->
            when (e) {
                SettingsError.InvalidNightWindowTime -> {
                    if (LocalTimeHm.parse(start) == null) errors[SettingsField.NIGHT_START] = e.message
                    if (LocalTimeHm.parse(end) == null) errors[SettingsField.NIGHT_END] = e.message
                }
                else -> errors[SettingsField.NIGHT_END] = e.message
            }
        }
        if (errors.isNotEmpty()) return DraftResult.Invalid(errors)
        val window = NightWindow(LocalTimeHm.parse(start)!!, LocalTimeHm.parse(end)!!)
        return DraftResult.Ok(base.copy(gates = GateConfig(g1, g3, window)))
    }

    private fun gateErrors(
        gate: Gate,
        number: SettingsField,
        location: SettingsField,
        landmark: SettingsField,
        out: MutableMap<SettingsField, String>,
    ) {
        (SettingsValidator.validateGate(gate) as? ValidationResult.Invalid)?.errors?.forEach { e ->
            val field = (e as? SettingsError.InvalidGateField)?.field ?: return@forEach
            out[when (field) {
                GateField.NUMBER -> number
                GateField.LOCATION -> location
                GateField.LANDMARK -> landmark
            }] = e.message
        }
    }
}

object QuickReplyDraft {
    const val MAX_LENGTH = 200

    /** Rows with both texts blank are dropped. A text longer than [MAX_LENGTH] rejects the list. */
    fun applyTo(base: Settings, rows: List<QuickReply>): DraftResult {
        val cleaned = rows.map { QuickReply(it.textEn.trim(), it.textHi.trim()) }
            .filter { it.textEn.isNotEmpty() || it.textHi.isNotEmpty() }
        if (cleaned.any { it.textEn.length > MAX_LENGTH || it.textHi.length > MAX_LENGTH }) {
            return DraftResult.Invalid(
                mapOf(SettingsField.QUICK_REPLIES to "Each quick reply must be at most $MAX_LENGTH characters"),
            )
        }
        return DraftResult.Ok(base.copy(quickReplies = cleaned))
    }
}
