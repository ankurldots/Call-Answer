package com.callanswer.agent.tools

import com.callanswer.agent.notify.MapSmsOutcome
import com.callanswer.agent.notify.MapSmsReason
import com.callanswer.agent.notify.MapSmsService
import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.sonic.ToolSpecs
import com.callanswer.core.model.Language
import com.callanswer.core.tenant.UserId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The Caller number of a call from the call state: E.164, or null when hidden. Never a tool input. */
fun interface CallerNumberSource {
    fun callerNumber(callState: CallState): String?
}

/**
 * `send_map_sms` (Requirements 10.5, 10.7, 10.8, 10.10, 10.12, 10.16). Takes no input. Returns `SENT`, `ALREADY_SENT`,
 * or `SAY_VERBALLY` with a `reason` and a `speak_exactly` text, so that Sonic says the location verbally.
 */
class SendMapSmsTool(
    private val service: MapSmsService,
    private val callerNumber: CallerNumberSource,
    private val traceFor: (CallState) -> CallTrace? = { null },
) : AgentTool {
    override val spec = ToolSpecs.all.first { it.name == ToolSpecs.SEND_MAP_SMS }

    override suspend fun execute(args: JsonObject, callState: CallState): JsonObject {
        // Stalling_Mode never texts a real location.
        if (callState.stallingMode) return say(MapSmsReason.NOT_CONFIGURED, callState.language)
        val outcome = service.sendAndAwait(
            userId = UserId(callState.userId),
            callId = callState.callId,
            callerNumber = callerNumber.callerNumber(callState),
            mapLocation = callState.frozenSettings.mapLocation,
            language = callState.language,
            trace = traceFor(callState),
        )
        return when (outcome) {
            MapSmsOutcome.Sent -> buildJsonObject { put("status", JsonPrimitive("SENT")) }
            MapSmsOutcome.AlreadySent -> buildJsonObject { put("status", JsonPrimitive("ALREADY_SENT")) }
            is MapSmsOutcome.SayVerbally -> say(outcome.reason, callState.language)
        }
    }

    private fun say(reason: MapSmsReason, language: Language): JsonObject = buildJsonObject {
        put("status", JsonPrimitive("SAY_VERBALLY"))
        put("reason", JsonPrimitive(reason.name))
        put("speak_exactly", JsonPrimitive(sayText(language)))
    }

    private fun sayText(language: Language): String = when (language) {
        Language.EN -> "I could not send the text message. Please follow the directions I just gave you."
        Language.HI -> "मैं संदेश नहीं भेज सका। कृपया अभी बताई गई दिशाओं का पालन करें।"
    }
}
