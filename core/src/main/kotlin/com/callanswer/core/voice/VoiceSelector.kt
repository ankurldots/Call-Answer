package com.callanswer.core.voice

import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender

/** A synthesizer voice. [gender] is null when unknown. */
data class VoiceInfo(val id: String, val language: Language, val gender: VoiceGender?, val bundled: Boolean)

/** The chosen voice; [substituted] is true when it does not have the requested gender. */
data class VoiceChoice(val voice: VoiceInfo, val substituted: Boolean)

object VoiceSelector {
    /**
     * Picks a voice of [language]: same gender first, bundled before non-bundled (ties keep list order).
     * If no same-gender voice exists, any voice of the language (bundled first) is used and flagged as
     * a substitution. Returns null when no voice exists for the language.
     */
    fun select(want: VoiceGender, language: Language, available: List<VoiceInfo>): VoiceChoice? {
        val ofLanguage = available.filter { it.language == language }
        val sameGender = ofLanguage.filter { it.gender == want }
        sameGender.firstOrNull { it.bundled }?.let { return VoiceChoice(it, false) }
        sameGender.firstOrNull()?.let { return VoiceChoice(it, false) }
        val any = ofLanguage.firstOrNull { it.bundled } ?: ofLanguage.firstOrNull() ?: return null
        return VoiceChoice(any, true)
    }
}
