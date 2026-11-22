package com.callanswer.agent.tools

import com.callanswer.agent.sonic.ToolSpec
import com.callanswer.agent.sonic.ToolSpecs
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Language
import com.callanswer.core.stalling.FictitiousGate
import com.callanswer.core.stalling.FictitiousReplyLibrary
import com.callanswer.core.stalling.PiiGuard
import com.callanswer.core.stalling.PromptBuilder
import com.callanswer.core.stalling.StallTopic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Real values that must never reach a Stalling_Mode result (Requirements 9.9, 12.6, 12.12, 20.9). */
internal object StallingSupport {
    fun protectedValues(s: FrozenSettings): Set<String> {
        val v = mutableSetOf<String>()
        v += s.protectedTerms
        listOfNotNull(s.mapLocation, s.address, s.addressHi, s.transferTarget).forEach { v += it }
        for (g in listOf(s.gates.gate1, s.gates.gate3)) { v += g.number; v += g.location; v += g.landmark }
        for (t in listOf(s.gates.nightWindow.start, s.gates.nightWindow.end)) {
            val hh = t.hour.toString().padStart(2, '0')
            val mm = t.minute.toString().padStart(2, '0')
            v += "$hh:$mm"
        }
        return v.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun guard(s: FrozenSettings): PiiGuard = PiiGuard(protectedValues(s))

    fun seed(callState: CallState, salt: Int = 0): Long = callState.callId.hashCode().toLong() * 31 + salt
}

object StallingResults {
    /** `get_delivery_directions` in Stalling_Mode: a fictitious reply, no real steps, gate or Map_Location. */
    fun directions(callState: CallState, language: Language): JsonObject {
        val lib = FictitiousReplyLibrary(StallingSupport.protectedValues(callState.frozenSettings))
        val n = callState.counters.merge("stalling_directions", 1) { a, b -> a + b } ?: 1
        val text = lib.reply(StallTopic.GENERIC, language, StallingSupport.seed(callState, n))
        return buildJsonObject {
            put("status", JsonPrimitive("OK"))
            put("speak_exactly", JsonPrimitive(text))
        }
    }
}

/**
 * `get_stalling_persona`: a fictitious identity that stays the same for the whole call because it is seeded by the
 * call identifier. The result is checked with the PII_Guard when it is built; a candidate that contains a protected
 * value is replaced by the next candidate.
 */
class GetStallingPersonaTool : AgentTool {
    override val spec: ToolSpec = ToolSpecs.all.first { it.name == ToolSpecs.GET_STALLING_PERSONA }

    override suspend fun execute(args: JsonObject, callState: CallState): JsonObject {
        val settings = callState.frozenSettings
        val guard = StallingSupport.guard(settings)
        val language = callState.language
        for (attempt in 0 until MAX_ATTEMPTS) {
            val seed = StallingSupport.seed(callState) + attempt
            val rnd = kotlin.random.Random(seed)
            val name = NAMES[rnd.nextInt(NAMES.size)]
            val city = CITIES[rnd.nextInt(CITIES.size)]
            val gate = FictitiousGate.answer(settings.gates, language, seed)
            val text = PromptBuilder.systemPrompt(language) +
                " Your name is $name and you live in $city. If asked about a gate, say: $gate"
            if (guard.containsProtected(text)) continue
            return buildJsonObject {
                put("status", JsonPrimitive("OK"))
                put("persona", buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("city", JsonPrimitive(city))
                    put("gate", JsonPrimitive(gate))
                    put("text", JsonPrimitive(text))
                })
            }
        }
        val text = PromptBuilder.systemPrompt(language)
        return buildJsonObject {
            put("status", JsonPrimitive("OK"))
            put("persona", buildJsonObject { put("text", JsonPrimitive(text)) })
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 12
        val NAMES = listOf("Meena Gupta", "Ramesh Yadav", "Sunita Rao", "Anil Mehra", "Kavita Joshi")
        val CITIES = listOf("Indore", "Kanpur", "Nagpur", "Jaipur", "Lucknow")
    }
}
