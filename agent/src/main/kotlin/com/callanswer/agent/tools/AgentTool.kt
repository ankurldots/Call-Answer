package com.callanswer.agent.tools

import com.callanswer.agent.sonic.ToolSpec
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Read and write view of the call that a tool sees. The Caller number and the User_Mobile are not part of
 * the tool inputs; tools get them from here when they need them.
 */
interface CallState {
    val userId: String
    val frozenSettings: FrozenSettings
    var language: Language
    val stallingMode: Boolean
    var intent: IntentCategory?
    val counters: MutableMap<String, Int>
}

/** In-memory [CallState] used by the call session. */
class MutableCallState(
    override val userId: String,
    override val frozenSettings: FrozenSettings,
    override var language: Language = Language.HI,
    override val stallingMode: Boolean = false,
    override var intent: IntentCategory? = null,
    override val counters: MutableMap<String, Int> = mutableMapOf(),
) : CallState

/** One Agent_Tool. Tools are plain classes; `ToolRegistry` registers them. */
interface AgentTool {
    val spec: ToolSpec
    val name: String get() = spec.name

    /**
     * Runs the tool with arguments that passed [ToolArgs.validate]. Tool specific checks (for example the
     * four intent categories) belong here and are returned as a `REJECTED` result, not thrown.
     */
    suspend fun execute(args: JsonObject, callState: CallState): JsonObject
}

object ToolResults {
    fun ok(): JsonObject = buildJsonObject { put("status", JsonPrimitive("OK")) }

    fun rejected(error: String, extra: Map<String, String?> = emptyMap()): JsonObject = buildJsonObject {
        put("status", JsonPrimitive("REJECTED"))
        put("error", JsonPrimitive(error))
        extra.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }
}

/** Result of schema validation. */
sealed interface ArgCheck {
    data object Valid : ArgCheck
    data class Invalid(val field: String, val message: String) : ArgCheck
}

/** Validates tool arguments against the subset of JSON Schema used by `ToolSpecs`. */
object ToolArgs {
    fun validate(spec: ToolSpec, args: JsonObject): ArgCheck {
        val schema = spec.schemaObject
        val props = (schema["properties"] as? JsonObject) ?: JsonObject(emptyMap())
        val required = (schema["required"] as? kotlinx.serialization.json.JsonArray)
            ?.map { (it as JsonPrimitive).content } ?: emptyList()
        for (r in required) if (r !in args) return ArgCheck.Invalid(r, "missing required field")
        for ((key, value) in args) {
            val prop = props[key] as? JsonObject ?: return ArgCheck.Invalid(key, "unknown field")
            val type = (prop["type"] as? JsonPrimitive)?.content
            val p = value as? JsonPrimitive ?: return ArgCheck.Invalid(key, "expected $type")
            val typeOk = when (type) {
                "string" -> p.isString
                "boolean" -> !p.isString && (p.content == "true" || p.content == "false")
                "integer" -> !p.isString && p.content.toLongOrNull() != null
                "number" -> !p.isString && p.content.toDoubleOrNull() != null
                else -> true
            }
            if (!typeOk) return ArgCheck.Invalid(key, "expected $type")
            val enum = (prop["enum"] as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonPrimitive).content }
            if (enum != null && p.content !in enum) return ArgCheck.Invalid(key, "must be one of $enum")
        }
        return ArgCheck.Valid
    }

    /** Validates, then executes; invalid arguments give a `REJECTED` result with `INVALID_ARGUMENT` and the field. */
    suspend fun run(tool: AgentTool, args: JsonObject, callState: CallState): JsonObject =
        when (val check = validate(tool.spec, args)) {
            ArgCheck.Valid -> tool.execute(args, callState)
            is ArgCheck.Invalid -> ToolResults.rejected("INVALID_ARGUMENT", mapOf("field" to check.field, "message" to check.message))
        }
}
