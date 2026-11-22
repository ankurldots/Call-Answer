package com.callanswer.core.gate

import com.callanswer.core.model.Gate
import com.callanswer.core.model.Language
import com.callanswer.core.model.NightWindow

/**
 * Renders Colony_Gate_Info. Output contains only gate number, location and landmark,
 * plus Night_Window times when [includeWindowTimes] is set or both gates are stated.
 * It never contains the Map_Location or any other address (Requirement 11.12).
 */
object GateInfoRenderer {
    fun render(selection: GateSelection, language: Language, includeWindowTimes: Boolean): String =
        when (selection) {
            is GateSelection.Single -> {
                val base = gateSentence(selection.gate, language)
                val w = selection.window
                if (includeWindowTimes && w != null) "$base ${windowTimesSentence(w, language)}" else base
            }
            is GateSelection.Both -> {
                val both = "${gateSentence(selection.gate1, language)} ${gateSentence(selection.gate3, language)}"
                "$both ${windowTimesSentence(selection.window, language)}"
            }
        }

    private fun gateSentence(g: Gate, language: Language): String = when (language) {
        Language.EN -> "Gate ${g.number} is at ${g.location}, ${g.landmark}."
        Language.HI -> "गेट नंबर ${g.number} ${g.location}, ${g.landmark} है।"
    }

    private fun windowTimesSentence(w: NightWindow, language: Language): String = when (language) {
        Language.EN -> "The night window is from ${w.start} to ${w.end}."
        Language.HI -> "रात का समय ${w.start} से ${w.end} तक है।"
    }
}
