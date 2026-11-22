package com.callanswer.core.stalling

import com.callanswer.core.model.GateConfig
import com.callanswer.core.model.Language
import kotlin.random.Random

/**
 * Fictitious gate information for a Caller who asks for the gate (Requirement 11.11).
 * Numbers and times are spelled out, and every pool entry (and the final text) is checked against
 * the configured gate numbers, locations, landmarks and Night_Window times.
 */
object FictitiousGate {

    private val EN_NUMBERS = listOf("five", "six", "seven", "eight", "nine", "twelve")
    private val EN_LOCATIONS = listOf("the main market lane", "the temple road", "the back lane by the park", "the old school road")
    private val EN_LANDMARKS = listOf("next to the sweet shop", "behind the water tank", "beside the dispensary", "across from the bus stop")
    private val EN_TIMES = listOf("eleven at night", "midnight", "four in the morning", "nine in the evening")

    private val HI_NUMBERS = listOf("पाँच", "छह", "सात", "आठ", "नौ", "बारह")
    private val HI_LOCATIONS = listOf("मुख्य बाज़ार की गली", "मंदिर रोड", "पार्क के पीछे वाली गली", "पुराने स्कूल की सड़क")
    private val HI_LANDMARKS = listOf("मिठाई की दुकान के बगल में", "पानी की टंकी के पीछे", "दवाखाने के पास", "बस स्टॉप के सामने")
    private val HI_TIMES = listOf("रात ग्यारह बजे", "आधी रात", "सुबह चार बजे", "शाम नौ बजे")

    private const val MAX_ATTEMPTS = 24

    fun answer(cfg: GateConfig, language: Language, seed: Long): String {
        val forbidden = forbiddenValues(cfg)
        fun clean(s: String): Boolean {
            val l = s.lowercase()
            return forbidden.none { l.contains(it) }
        }
        val rnd = Random(seed)
        repeat(MAX_ATTEMPTS) {
            val text = compose(language, rnd)
            if (clean(text)) return text
        }
        // Pools are exhausted by the configuration: fall back to a text with no gate details at all.
        val fallback = if (language == Language.HI) "मुझे ठीक से याद नहीं है।" else "I do not remember exactly."
        return if (clean(fallback)) fallback else ""
    }

    private fun compose(language: Language, rnd: Random): String {
        return if (language == Language.HI) {
            "गेट नंबर ${HI_NUMBERS.pick(rnd)}, ${HI_LOCATIONS.pick(rnd)}, ${HI_LANDMARKS.pick(rnd)}। रात में ${HI_TIMES.pick(rnd)} तक खुला रहता है।"
        } else {
            "It is gate number ${EN_NUMBERS.pick(rnd)}, on ${EN_LOCATIONS.pick(rnd)}, ${EN_LANDMARKS.pick(rnd)}. At night it stays open until ${EN_TIMES.pick(rnd)}."
        }
    }

    private fun List<String>.pick(rnd: Random): String = this[rnd.nextInt(size)]

    private fun forbiddenValues(cfg: GateConfig): List<String> {
        val v = mutableListOf<String>()
        for (g in listOf(cfg.gate1, cfg.gate3)) { v += g.number; v += g.location; v += g.landmark }
        for (t in listOf(cfg.nightWindow.start, cfg.nightWindow.end)) {
            val hh = t.hour.toString().padStart(2, '0')
            val mm = t.minute.toString().padStart(2, '0')
            v += "$hh:$mm"; v += "$hh.$mm"; v += "$hh$mm"
        }
        return v.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
    }
}
