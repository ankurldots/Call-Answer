package com.callanswer.core.delivery

import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Language

fun interface StepTranslator {
    fun translate(text: String, from: Language, to: Language): String?
}

object DeliveryDirectionsRenderer {
    const val PLACEHOLDER = "{map_location}"
    private val WHITESPACE = Regex("""\s+""")

    /** Returns `[gateInfoText, step1, step2, ...]`. */
    fun render(
        steps: List<DeliveryStep>,
        language: Language,
        mapLocation: String?,
        gateInfoText: String,
        translator: StepTranslator?,
    ): List<String> = buildList {
        add(gateInfoText)
        steps.forEach { add(renderStep(it, language, mapLocation, translator)) }
    }

    private fun renderStep(
        step: DeliveryStep,
        language: Language,
        mapLocation: String?,
        translator: StepTranslator?,
    ): String {
        val (own, other, otherLang) = when (language) {
            Language.EN -> Triple(step.textEn, step.textHi, Language.HI)
            Language.HI -> Triple(step.textHi, step.textEn, Language.EN)
        }
        val text = when {
            own.isNotBlank() -> own
            other.isBlank() -> own
            else -> translator?.translate(other, otherLang, language)?.takeIf { it.isNotBlank() } ?: other
        }
        return substitute(text, mapLocation)
    }

    private fun substitute(text: String, mapLocation: String?): String {
        // A link is texted to the Caller, never read out.
        val loc = mapLocation?.trim().orEmpty().takeUnless { it.contains("://") }.orEmpty()
        return if (loc.isNotEmpty()) {
            text.replace(PLACEHOLDER, loc)
        } else {
            text.replace(PLACEHOLDER, "").replace(WHITESPACE, " ").trim()
        }
    }
}
