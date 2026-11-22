package com.callanswer.app.ui.screens

import com.callanswer.app.data.SyncResult
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.CostRates
import com.callanswer.core.model.FallbackRoute
import com.callanswer.core.model.Language
import com.callanswer.core.model.Settings
import com.callanswer.core.settings.SettingsValidator
import com.callanswer.core.settings.ValidationResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Voice ids the User can pick (kiara and arjun are the defaults, tiffany and matthew the alternatives). */
val VOICE_IDS: List<String> = listOf("kiara", "arjun", "tiffany", "matthew")

const val RETENTION_MIN_DAYS = 1
const val RETENTION_MAX_DAYS = 365

/** Settings document of the server (or the cached copy with the pending edit) as [Settings]; defaults when unreadable. */
fun settingsFromJson(doc: JsonObject?): Settings =
    if (doc == null) Settings() else runCatching { CoreJson.decodeFromJsonElement(Settings.serializer(), doc) }.getOrDefault(Settings())

/** Fields of [new] that differ from [old], as JSON. `version` is set by the server and never sent as an edit. */
fun changedFields(old: Settings, new: Settings): JsonObject {
    val a = CoreJson.encodeToJsonElement(Settings.serializer(), old) as JsonObject
    val b = CoreJson.encodeToJsonElement(Settings.serializer(), new) as JsonObject
    return JsonObject(b.filter { (k, v) -> k != "version" && a[k] != v })
}

/** Requirement 6 criterion 4: warn while the notice is off and the calls are recorded. */
fun disclosureOffWarning(s: Settings): Boolean = !s.disclosureEnabled && s.recordingEnabled

/** What the User sees after a save. */
sealed interface EditOutcome {
    /** The server stored the edit and the cache holds the stored document. */
    data object Applied : EditOutcome
    /** Kept on the device and not yet applied (offline, server error, or retries used up). */
    data object NotYetApplied : EditOutcome
    /** Fields changed on both sides; open [Routes.SETTINGS_CONFLICTS]. */
    data object NeedsChoice : EditOutcome
    /** The server rejected [field] (HTTP 400); the pending edit was dropped and the saved value stays. */
    data class Rejected(val field: String, val message: String) : EditOutcome
}

fun SyncResult.toOutcome(): EditOutcome = when (this) {
    is SyncResult.Saved, is SyncResult.UpToDate -> EditOutcome.Applied
    is SyncResult.NeedsResolution -> EditOutcome.NeedsChoice
    is SyncResult.Invalid -> EditOutcome.Rejected(field, message)
    SyncResult.RetriesExhausted, is SyncResult.Failed -> EditOutcome.NotYetApplied
}

/** Form of the cloud Agent settings (Requirement 20 criterion 1 fields that have no other screen). */
data class AgentDraft(
    val userMobile: String,
    val greetingName: String,
    val retentionDays: String,
    val exotelPaise: String,
    val sonicInPaise: String,
    val sonicOutPaise: String,
    val monthlyLimitInr: String,
    val fallbackRoute: FallbackRoute,
    val defaultLanguage: Language,
    val recordingEnabled: Boolean,
) {
    companion object {
        fun from(s: Settings) = AgentDraft(
            userMobile = s.userMobile.orEmpty(),
            greetingName = s.greetingName.orEmpty(),
            retentionDays = s.retentionDays.toString(),
            exotelPaise = s.costRates.exotelPerMinutePaise.toString(),
            sonicInPaise = s.costRates.sonicInputPerMinutePaise.toString(),
            sonicOutPaise = s.costRates.sonicOutputPerMinutePaise.toString(),
            monthlyLimitInr = s.monthlyLimitInr?.toString().orEmpty(),
            fallbackRoute = s.fallbackRoute,
            defaultLanguage = s.defaultLanguage,
            recordingEnabled = s.recordingEnabled,
        )
    }

    /** A blank User_Mobile, greeting name or monthly limit clears the value. */
    fun applyTo(base: Settings): AgentDraftResult {
        val errors = mutableMapOf<AgentField, String>()
        val mobile = userMobile.trim()
        if (mobile.isNotEmpty()) {
            (SettingsValidator.validateUserMobile(mobile) as? ValidationResult.Invalid)
                ?.let { errors[AgentField.USER_MOBILE] = it.errors.first().message }
        }
        val days = retentionDays.trim().toIntOrNull()
        if (days == null || days !in RETENTION_MIN_DAYS..RETENTION_MAX_DAYS) {
            errors[AgentField.RETENTION] = "Enter a whole number of days from $RETENTION_MIN_DAYS to $RETENTION_MAX_DAYS"
        }
        val rates = listOf(AgentField.RATE_EXOTEL to exotelPaise, AgentField.RATE_SONIC_IN to sonicInPaise, AgentField.RATE_SONIC_OUT to sonicOutPaise)
            .map { (field, text) ->
                val v = text.trim().toLongOrNull()
                if (v == null || v < 0) errors[field] = "Enter a whole number of paise, 0 or more"
                v
            }
        val limitText = monthlyLimitInr.trim()
        val limit = limitText.toIntOrNull()
        if (limitText.isNotEmpty() && (limit == null || limit < 0)) {
            errors[AgentField.MONTHLY_LIMIT] = "Enter a whole number of rupees, 0 or more, or leave empty for no limit"
        }
        if (errors.isNotEmpty()) return AgentDraftResult.Invalid(errors)
        return AgentDraftResult.Ok(
            base.copy(
                userMobile = mobile.ifEmpty { null },
                greetingName = greetingName.trim().ifEmpty { null },
                retentionDays = days!!,
                costRates = CostRates(rates[0]!!, rates[1]!!, rates[2]!!),
                monthlyLimitInr = limit,
                fallbackRoute = fallbackRoute,
                defaultLanguage = defaultLanguage,
                recordingEnabled = recordingEnabled,
            ),
        )
    }
}

enum class AgentField { USER_MOBILE, RETENTION, RATE_EXOTEL, RATE_SONIC_IN, RATE_SONIC_OUT, MONTHLY_LIMIT }

sealed interface AgentDraftResult {
    data class Ok(val settings: Settings) : AgentDraftResult
    data class Invalid(val errors: Map<AgentField, String>) : AgentDraftResult
}

/** Agent switch rule: it can be turned on only after the consent is accepted; turning it off is always allowed. */
fun agentSwitchEnabled(consent: ConsentStatus, currentlyOn: Boolean): Boolean = consent.unlocksAgentAndCodes() || currentlyOn

/** Text of a JSON value for the conflict screen. */
fun displayValue(v: JsonElement?): String = when (v) {
    null -> "-"
    is JsonPrimitive -> if (v.isString) v.content else v.toString()
    else -> v.toString()
}
