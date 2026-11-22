package com.callanswer.app.audio

import android.content.Context
import android.media.AudioAttributes
import com.callanswer.app.BuildConfig

/**
 * Debug-only A/B setting for the audio usage of in-call Agent speech (Settings > Advanced). Release builds
 * always use [VOICE_COMMUNICATION] first. The chosen usage is tried first; the others stay as fallbacks.
 */
enum class AgentVoiceUsage(val usage: Int) {
    VOICE_COMMUNICATION(AudioAttributes.USAGE_VOICE_COMMUNICATION),
    ASSISTANT(AudioAttributes.USAGE_ASSISTANT),
    MEDIA(AudioAttributes.USAGE_MEDIA);

    companion object {
        private const val PREFS = "agent_voice_usage"
        private const val KEY = "usage"

        /** Chosen usage first, then the remaining ones in default order. */
        fun order(chosen: AgentVoiceUsage): List<Int> =
            (listOf(chosen) + entries.filter { it != chosen }).map { it.usage }

        fun get(context: Context): AgentVoiceUsage {
            if (!BuildConfig.DEBUG) return VOICE_COMMUNICATION
            val name = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == name } ?: VOICE_COMMUNICATION
        }

        fun set(context: Context, value: AgentVoiceUsage) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
        }

        fun orderFor(context: Context): List<Int> = order(get(context))
    }
}
