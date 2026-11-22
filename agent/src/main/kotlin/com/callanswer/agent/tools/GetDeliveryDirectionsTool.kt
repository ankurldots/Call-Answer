package com.callanswer.agent.tools

import com.callanswer.agent.Clock
import com.callanswer.agent.sonic.ToolSpecs
import com.callanswer.core.delivery.DeliveryDirectionsRenderer
import com.callanswer.core.gate.GateInfoRenderer
import com.callanswer.core.gate.GateSelector
import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId

/** Asks the server to push a notification to the Companion_App of [userId]. */
fun interface MapLocationMissingPush {
    fun request(userId: String, kind: String)
}

/**
 * `get_delivery_directions` (Requirements 10.1 to 10.4, 10.6, 10.11, 7.7, 7.8).
 *
 * The text is the Colony_Gate_Info followed by the Delivery_Steps in order. The first call and the
 * three repetitions return `OK`; later calls return `REPETITION_LIMIT` with the instruction to end the call.
 */
class GetDeliveryDirectionsTool(
    private val clock: Clock,
    private val defaultLanguage: Language,
    private val push: MapLocationMissingPush = MapLocationMissingPush { _, _ -> },
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) : AgentTool {
    override val spec = ToolSpecs.all.first { it.name == ToolSpecs.GET_DELIVERY_DIRECTIONS }

    override suspend fun execute(args: JsonObject, callState: CallState): JsonObject {
        // Stalling_Mode never receives real gate, step, or location data.
        if (callState.stallingMode) return StallingResults.directions(callState, resolveLanguage(args))

        val used = callState.counters[COUNTER] ?: 0
        if (used > MAX_REPETITIONS) {
            return buildJsonObject {
                put("status", JsonPrimitive("REPETITION_LIMIT"))
                put("speak_exactly", JsonPrimitive(limitText(resolveLanguage(args))))
                put("then", JsonPrimitive("end_call"))
            }
        }
        callState.counters[COUNTER] = used + 1

        val language = resolveLanguage(args)
        val settings = callState.frozenSettings
        val mapLocation = settings.mapLocation?.trim()?.takeIf { it.isNotEmpty() }
        if (mapLocation == null) push.request(callState.userId, "MAP_LOCATION_MISSING")

        val parts = DeliveryDirectionsRenderer.render(
            steps = settings.deliverySteps,
            language = language,
            mapLocation = mapLocation,
            gateInfoText = gateInfoText(callState, language),
            translator = null,
        )
        val singleLanguage = settings.deliverySteps.any { it.isSingleLanguage() }
        return buildJsonObject {
            put("status", JsonPrimitive("OK"))
            put("speak_exactly", JsonPrimitive(parts.joinToString(" ")))
            put("repetition", JsonPrimitive(used))
            put("repetitions_left", JsonPrimitive(MAX_REPETITIONS - used))
            if (singleLanguage) put("note", JsonPrimitive(SINGLE_LANGUAGE_NOTE))
            if (mapLocation == null) put("map_location", JsonPrimitive("MAP_LOCATION_MISSING"))
        }
    }

    private fun resolveLanguage(args: JsonObject): Language =
        when ((args["language"] as? JsonPrimitive)?.content) {
            "hi" -> Language.HI
            "en" -> Language.EN
            else -> defaultLanguage
        }

    private fun gateInfoText(callState: CallState, language: Language): String {
        val now = runCatching {
            val t = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone)
            LocalTimeHm(t.hour, t.minute)
        }.getOrNull()
        val selection = GateSelector.selectGate(now, callState.frozenSettings.gates)
        return GateInfoRenderer.render(selection, language, includeWindowTimes = false)
    }

    private fun limitText(language: Language): String = when (language) {
        Language.EN -> "I cannot repeat the directions any more. Goodbye."
        Language.HI -> "मैं अब दिशाएँ दोबारा नहीं बता सकता। नमस्ते।"
    }

    private fun DeliveryStep.isSingleLanguage(): Boolean = textEn.isBlank() != textHi.isBlank()

    companion object {
        const val COUNTER = "directions"
        const val MAX_REPETITIONS = 3
        const val SINGLE_LANGUAGE_NOTE =
            "Some steps are in one language only. Speak those steps in the Caller's language without changing the facts."
    }
}
