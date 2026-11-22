package com.callanswer.agent.tools

import com.callanswer.agent.Clock
import com.callanswer.agent.SystemClock
import com.callanswer.agent.sonic.ToolSpec
import com.callanswer.agent.sonic.ToolSpecs
import com.callanswer.core.gate.GateInfoRenderer
import com.callanswer.core.gate.GateSelection
import com.callanswer.core.gate.GateSelector
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.stalling.FictitiousGate
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId

/** Receives Call_Log notes written by tools (a clock failure, a fictitious gate answer). */
fun interface ToolNoteSink {
    suspend fun note(callState: CallState, text: String)
}

/**
 * `get_gate_info`: selects the gate with `:core` [GateSelector] at the Asia/Kolkata minute and renders the text with
 * [GateInfoRenderer]. The result has only gate number, location, landmark and text; the window times appear only on request.
 */
class GetGateInfoTool(
    private val clock: Clock = SystemClock,
    private val notes: ToolNoteSink = ToolNoteSink { _, _ -> },
    private val zone: ZoneId = HOUSEHOLD_ZONE,
) : AgentTool {
    override val spec: ToolSpec = ToolSpecs.all.first { it.name == ToolSpecs.GET_GATE_INFO }

    override suspend fun execute(args: JsonObject, callState: CallState): JsonObject {
        val language = when ((args["language"] as? JsonPrimitive)?.content) {
            "hi" -> Language.HI
            "en" -> Language.EN
            else -> callState.language
        }
        val includeWindow = (args["include_window_times"] as? JsonPrimitive)?.booleanOrNull ?: false
        val cfg = callState.frozenSettings.gates

        if (callState.stallingMode) {
            val millis = try { clock.nowMillis() } catch (e: CancellationException) { throw e } catch (e: Exception) { 0L }
            notes.note(callState, "get_gate_info answered with a fictitious gate")
            return buildJsonObject {
                put("status", JsonPrimitive("OK"))
                put("speak_exactly", JsonPrimitive(FictitiousGate.answer(cfg, language, millis / 60_000L)))
            }
        }

        val now: LocalTimeHm? = try {
            val t = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone)
            LocalTimeHm(t.hour, t.minute)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notes.note(callState, "get_gate_info clock unavailable: ${e.message ?: e::class.simpleName}")
            null
        }

        val selection = GateSelector.selectGate(now, cfg)
        val text = GateInfoRenderer.render(selection, language, includeWindow)
        return buildJsonObject {
            put("status", JsonPrimitive("OK"))
            when (selection) {
                is GateSelection.Single -> {
                    put("gate_number", JsonPrimitive(selection.gate.number))
                    put("location", JsonPrimitive(selection.gate.location))
                    put("landmark", JsonPrimitive(selection.gate.landmark))
                    if (includeWindow) {
                        put("night_window_start", JsonPrimitive(selection.window?.start?.toString()))
                        put("night_window_end", JsonPrimitive(selection.window?.end?.toString()))
                    }
                }
                is GateSelection.Both -> {
                    put("clock", JsonPrimitive("UNAVAILABLE"))
                    put("gates", kotlinx.serialization.json.JsonArray(listOf(selection.gate1, selection.gate3).map {
                        buildJsonObject {
                            put("gate_number", JsonPrimitive(it.number))
                            put("location", JsonPrimitive(it.location))
                            put("landmark", JsonPrimitive(it.landmark))
                        }
                    }))
                    put("night_window_start", JsonPrimitive(selection.window.start.toString()))
                    put("night_window_end", JsonPrimitive(selection.window.end.toString()))
                }
            }
            put("speak_exactly", JsonPrimitive(text))
        }
    }

    companion object {
        val HOUSEHOLD_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    }
}
