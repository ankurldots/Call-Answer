package com.callanswer.app.tts

import com.callanswer.core.model.VoiceGender

/** Where a Settings voice sample comes from, in the order a call tries them. */
enum class SampleSource { POLLY_CACHE, PIPER, ANDROID_TTS }

object SampleSourcePlan {
    /**
     * Order of sources for one sample. Polly audio is only used when signed in, for the female voice (Kajal) and
     * when that exact sentence is already on the phone; Piper and then the platform TTS always follow.
     */
    fun order(signedIn: Boolean, gender: VoiceGender, pollyCached: Boolean): List<SampleSource> =
        buildList {
            if (signedIn && gender == VoiceGender.FEMALE && pollyCached) add(SampleSource.POLLY_CACHE)
            add(SampleSource.PIPER)
            add(SampleSource.ANDROID_TTS)
        }

    /** True when the sample screen should suggest signing in (the natural voice needs an account). */
    fun suggestSignIn(signedIn: Boolean, accountAvailable: Boolean): Boolean = !signedIn && accountAvailable
}
