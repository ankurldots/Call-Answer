package com.callanswer.agent.tools

import com.callanswer.agent.sonic.ToolSpec
import com.callanswer.agent.sonic.ToolSpecs
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `log_note`: records the intent, the language, the Caller's message, or a short note.
 *
 * INTENT accepts only the four [IntentCategory] names; any other value is rejected with `INVALID_INTENT` and the
 * current category, and the stored category stays unchanged. Accepted values are stored in the call state before the
 * tool returns (no I/O, so far inside the 2 second limit). MESSAGE (at most 500 characters) and NOTE (at most 200)
 * go to the [ToolNoteSink] as `MESSAGE: ...` and `NOTE: ...` Call_Log notes.
 */
class LogNoteTool(
    private val notes: ToolNoteSink = ToolNoteSink { _, _ -> },
) : AgentTool {
    override val spec: ToolSpec = ToolSpecs.all.first { it.name == ToolSpecs.LOG_NOTE }

    override suspend fun execute(args: JsonObject, callState: CallState): JsonObject {
        val kind = (args["kind"] as? JsonPrimitive)?.content
        val value = (args["value"] as? JsonPrimitive)?.content ?: ""
        return when (kind) {
            "INTENT" -> {
                val category = IntentCategory.entries.firstOrNull { it.name == value }
                    ?: return ToolResults.rejected("INVALID_INTENT", mapOf("current" to callState.intent?.name))
                callState.intent = category
                ToolResults.ok()
            }
            "LANGUAGE" -> {
                callState.language = when (value) {
                    "hi" -> Language.HI
                    "en" -> Language.EN
                    else -> return ToolResults.rejected("INVALID_LANGUAGE", mapOf("current" to callState.language.name.lowercase()))
                }
                ToolResults.ok()
            }
            "MESSAGE" -> text("MESSAGE", value, MAX_MESSAGE, callState)
            "NOTE" -> text("NOTE", value, MAX_NOTE, callState)
            else -> ToolResults.rejected("INVALID_KIND")
        }
    }

    private suspend fun text(kind: String, value: String, max: Int, callState: CallState): JsonObject {
        if (value.length > max) return ToolResults.rejected("VALUE_TOO_LONG", mapOf("max" to max.toString()))
        notes.note(callState, "$kind: $value")
        return ToolResults.ok()
    }

    companion object {
        const val MAX_MESSAGE = 500
        const val MAX_NOTE = 200
    }
}
