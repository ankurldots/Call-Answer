package com.callanswer.agent.sonic

import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.model.VoiceSubstitution
import com.callanswer.core.voice.VoiceInfo
import com.callanswer.core.voice.VoiceSelector

/** The voice of one call. It is created once at call start and reused by every replacement Sonic session. */
data class CallVoice(val voiceId: String, val substitution: VoiceSubstitution?)

/**
 * Voice selection for the Sonic session (Requirements 7.3, 7.5, 7.6).
 * Defaults are `kiara` (female) and `arjun` (male); `tiffany` and `matthew` are selectable alternatives.
 */
object SonicVoices {
    const val DEFAULT_FEMALE = "kiara"
    const val DEFAULT_MALE = "arjun"
    const val ALT_FEMALE = "tiffany"
    const val ALT_MALE = "matthew"

    /** Voices offered per language. The defaults are marked bundled so that [VoiceSelector] prefers them. */
    val catalog: List<VoiceInfo> = listOf(
        VoiceInfo(DEFAULT_FEMALE, Language.EN, VoiceGender.FEMALE, bundled = true),
        VoiceInfo(DEFAULT_FEMALE, Language.HI, VoiceGender.FEMALE, bundled = true),
        VoiceInfo(DEFAULT_MALE, Language.EN, VoiceGender.MALE, bundled = true),
        VoiceInfo(DEFAULT_MALE, Language.HI, VoiceGender.MALE, bundled = true),
        VoiceInfo(ALT_FEMALE, Language.EN, VoiceGender.FEMALE, bundled = false),
        VoiceInfo(ALT_MALE, Language.EN, VoiceGender.MALE, bundled = false),
    )

    val selectableIds: Set<String> = catalog.map { it.id }.toSet()

    /**
     * Picks the voice for a call from the frozen settings. [preferredId] (the optional `voiceId` setting)
     * wins when it is available for [language]; otherwise the voice of [gender] is chosen with
     * [VoiceSelector]. A substitution is recorded only when the preferred voice is unavailable:
     * the preferred voice is [preferredId] if set, else the default voice of [gender].
     * [unavailable] lists voice ids that cannot be used right now.
     * Returns null only when no voice at all exists for [language].
     */
    fun resolve(
        gender: VoiceGender,
        language: Language,
        preferredId: String? = null,
        unavailable: Set<String> = emptySet(),
    ): CallVoice? {
        val available = catalog.filter { it.id !in unavailable }
        val preferred = preferredId?.takeIf { it.isNotBlank() }
            ?: if (gender == VoiceGender.FEMALE) DEFAULT_FEMALE else DEFAULT_MALE
        available.firstOrNull { it.id == preferred && it.language == language }
            ?.let { return CallVoice(it.id, null) }
        val choice = VoiceSelector.select(gender, language, available) ?: return null
        return CallVoice(choice.voice.id, VoiceSubstitution(language, choice.voice.id))
    }
}
