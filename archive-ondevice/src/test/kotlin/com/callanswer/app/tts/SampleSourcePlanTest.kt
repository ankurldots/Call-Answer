package com.callanswer.app.tts

import com.callanswer.app.cloudtts.SpokenSentence
import com.callanswer.app.cloudtts.SpokenTextCatalog
import com.callanswer.core.model.Language
import com.callanswer.core.model.Settings
import com.callanswer.core.model.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleSourcePlanTest {
    private val local = listOf(SampleSource.PIPER, SampleSource.ANDROID_TTS)

    @Test fun signedInFemaleCachedUsesPollyFirst() =
        assertEquals(listOf(SampleSource.POLLY_CACHE) + local, SampleSourcePlan.order(true, VoiceGender.FEMALE, true))

    @Test fun signedOutSkipsPollyEvenWhenCached() =
        assertEquals(local, SampleSourcePlan.order(false, VoiceGender.FEMALE, true))

    @Test fun notCachedOrMaleSkipsPolly() {
        assertEquals(local, SampleSourcePlan.order(true, VoiceGender.FEMALE, false))
        assertEquals(local, SampleSourcePlan.order(true, VoiceGender.MALE, true))
    }

    @Test fun signInHintOnlyWhenSignedOutAndAccountAvailable() {
        assertTrue(SampleSourcePlan.suggestSignIn(signedIn = false, accountAvailable = true))
        assertFalse(SampleSourcePlan.suggestSignIn(signedIn = true, accountAvailable = true))
        assertFalse(SampleSourcePlan.suggestSignIn(signedIn = false, accountAvailable = false))
    }

    @Test fun catalogContainsTheExactSettingsSamples() {
        val settings = Settings(agentName = "Asha")
        val all = SpokenTextCatalog.sentences(settings)
        for (lang in Language.entries) {
            assertTrue(SpokenSentence(lang, VoiceSamplePlayer.sampleText(lang, "Asha")) in all)
        }
    }
}
