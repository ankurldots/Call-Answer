package com.callanswer.agent.tools

import com.callanswer.agent.sonic.ToolSpec
import com.callanswer.agent.sonic.ToolSpecs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Receives the model's takeover request; the call session turns it into `CallPolicy.Event.TakeoverRequested`. */
fun interface TakeoverRequestSink {
    suspend fun requestTakeover(callState: CallState)
}

/** `request_takeover`: takes no input. The Caller number and User_Mobile are not tool inputs. */
class RequestTakeoverTool(private val sink: TakeoverRequestSink) : AgentTool {
    override val spec: ToolSpec = ToolSpecs.all.first { it.name == ToolSpecs.REQUEST_TAKEOVER }

    override suspend fun execute(args: JsonObject, callState: CallState): JsonObject {
        sink.requestTakeover(callState)
        return ToolResults.ok()
    }
}

/** Receives the model's end request; the implementation must wait for the last `mark` (see [EndAfterPlayback]). */
fun interface EndCallSink {
    suspend fun endCall(callState: CallState, reason: String)
}

/** `end_call`: returns at once; the call is closed by [EndCallSink] after the farewell has played. */
class EndCallTool(private val sink: EndCallSink) : AgentTool {
    override val spec: ToolSpec = ToolSpecs.all.first { it.name == ToolSpecs.END_CALL }

    override suspend fun execute(args: JsonObject, callState: CallState): JsonObject {
        val reason = (args["reason"] as? JsonPrimitive)?.content
        if (reason == null || reason !in ToolSpecs.END_REASONS) return ToolResults.rejected("INVALID_ARGUMENT", mapOf("field" to "reason"))
        sink.endCall(callState, reason)
        return buildOk(reason)
    }

    private fun buildOk(reason: String): JsonObject = JsonObject(mapOf("status" to JsonPrimitive("OK"), "reason" to JsonPrimitive(reason)))
}

/**
 * Runs the end action only after every `mark` sent to Exotel has been played back. Call [markSent] when a mark goes out and
 * [markPlayed] when Exotel echoes it. Not thread safe: use it from the single coroutine of one call.
 */
class EndAfterPlayback {
    private var pending = 0
    private var action: (suspend () -> Unit)? = null

    fun markSent() { pending++ }

    suspend fun markPlayed() {
        if (pending > 0) pending--
        runIfDue()
    }

    /** Runs [end] now when no mark is pending, otherwise after the last one played. A later request replaces an earlier one. */
    suspend fun request(end: suspend () -> Unit) {
        action = end
        runIfDue()
    }

    private suspend fun runIfDue() {
        val a = action ?: return
        if (pending == 0) {
            action = null
            a()
        }
    }
}
