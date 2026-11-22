package com.callanswer.agent.sonic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** One tool as registered in `promptStart`. [schema] is the JSON Schema of the input as a JSON string. */
data class ToolSpec(val name: String, val description: String, val schema: String) {
    val schemaObject: JsonObject get() = Json.parseToJsonElement(schema).jsonObject
}

/** The seven Agent_Tools of the design. The Caller number and the User_Mobile are never tool inputs. */
object ToolSpecs {
    const val GET_GATE_INFO = "get_gate_info"
    const val GET_DELIVERY_DIRECTIONS = "get_delivery_directions"
    const val SEND_MAP_SMS = "send_map_sms"
    const val REQUEST_TAKEOVER = "request_takeover"
    const val END_CALL = "end_call"
    const val LOG_NOTE = "log_note"
    const val GET_STALLING_PERSONA = "get_stalling_persona"

    val END_REASONS = listOf(
        "DELIVERY_DONE", "CALLER_DONE", "UNKNOWN_AFTER_CLARIFY", "CALLER_SILENCE",
        "REPEATS_EXHAUSTED", "MAX_DURATION", "ABUSIVE_LANGUAGE",
    )
    val NOTE_KINDS = listOf("INTENT", "LANGUAGE", "MESSAGE", "NOTE")

    private fun enumList(values: List<String>) = values.joinToString(",", "[", "]") { "\"$it\"" }

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{},"required":[]}"""

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            GET_GATE_INFO,
            "Returns the gate the delivery person should use now, with a text marked speak_exactly that you must read word for word.",
            """{"type":"object","properties":{"language":{"type":"string","enum":["hi","en"]},"include_window_times":{"type":"boolean"}},"required":[]}""",
        ),
        ToolSpec(
            GET_DELIVERY_DIRECTIONS,
            "Returns the step by step delivery directions as a text marked speak_exactly. Call it again when the caller asks for a repetition.",
            """{"type":"object","properties":{"language":{"type":"string","enum":["hi","en","other"]}},"required":["language"]}""",
        ),
        ToolSpec(
            SEND_MAP_SMS,
            "Sends the map link by SMS to the caller's own number. The number is known to the system; do not ask for it.",
            EMPTY_SCHEMA,
        ),
        ToolSpec(
            REQUEST_TAKEOVER,
            "Asks the person you answer for to take over the call, for example when nobody opens the door.",
            EMPTY_SCHEMA,
        ),
        ToolSpec(
            END_CALL,
            "Ends the call after your final words.",
            """{"type":"object","properties":{"reason":{"type":"string","enum":${enumList(END_REASONS)}}},"required":["reason"]}""",
        ),
        ToolSpec(
            LOG_NOTE,
            "Records the intent, the language, the caller's message, or a short note. Call it with kind INTENT as soon as you know why the caller called.",
            """{"type":"object","properties":{"kind":{"type":"string","enum":${enumList(NOTE_KINDS)}},"value":{"type":"string"}},"required":["kind","value"]}""",
        ),
        ToolSpec(
            GET_STALLING_PERSONA,
            "Returns a fictitious but consistent identity to use when keeping a suspicious caller talking.",
            EMPTY_SCHEMA,
        ),
    )

    val names: Set<String> = all.map { it.name }.toSet()
}
