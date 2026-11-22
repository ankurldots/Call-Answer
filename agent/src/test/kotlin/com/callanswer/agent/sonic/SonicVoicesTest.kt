package com.callanswer.agent.sonic

import com.callanswer.core.model.Language
import com.callanswer.core.model.VoiceGender
import com.callanswer.core.model.VoiceSubstitution
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SonicVoicesTest : FunSpec({
    test("defaults are kiara and arjun in both languages without substitution") {
        for (lang in Language.values()) {
            SonicVoices.resolve(VoiceGender.FEMALE, lang) shouldBe CallVoice("kiara", null)
            SonicVoices.resolve(VoiceGender.MALE, lang) shouldBe CallVoice("arjun", null)
        }
    }

    test("alternatives are selectable by voiceId") {
        SonicVoices.resolve(VoiceGender.FEMALE, Language.EN, "tiffany") shouldBe CallVoice("tiffany", null)
        SonicVoices.resolve(VoiceGender.MALE, Language.EN, "matthew") shouldBe CallVoice("matthew", null)
    }

    test("unavailable preferred voice is replaced by one of the same gender and recorded") {
        SonicVoices.resolve(VoiceGender.FEMALE, Language.HI, "tiffany") shouldBe
            CallVoice("kiara", VoiceSubstitution(Language.HI, "kiara"))
        SonicVoices.resolve(VoiceGender.FEMALE, Language.HI, unavailable = setOf("kiara")) shouldBe
            CallVoice("arjun", VoiceSubstitution(Language.HI, "arjun"))
        SonicVoices.resolve(VoiceGender.MALE, Language.EN, unavailable = setOf("arjun")) shouldBe
            CallVoice("matthew", VoiceSubstitution(Language.EN, "matthew"))
    }

    test("the voice is the same for the call regardless of later language changes") {
        val voice = SonicVoices.resolve(VoiceGender.FEMALE, Language.EN)!!
        // The call keeps this CallVoice; replacement sessions read voice.voiceId and never re-resolve.
        listOf(Language.HI, Language.EN, Language.HI).forEach { _ -> voice.voiceId shouldBe "kiara" }
    }
})
