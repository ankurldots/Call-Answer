package com.callanswer.app.cloudtts
import com.callanswer.app.tts.SentenceSplitter
import com.callanswer.core.engine.ResponseRenderer
import com.callanswer.core.engine.SpeechSpec
import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.model.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class SpokenTextCatalogTest {
    private val settings = Settings(
        mapLocation = "the blue gate",
        deliverySteps = listOf(DeliveryStep("1", "Go to {map_location}. Turn left.", "{map_location} पर जाइए।")),
    )
    private fun has(lang: Language, text: String) = SpokenSentence(lang, text) in SpokenTextCatalog.sentences(settings)
    /** Every sentence a call would synthesize for directions, at any time of day, is in the catalog. */
    @Test fun coversDirectionsAtAllTimes() {
        val frozen = settings.freeze()
        val all = SpokenTextCatalog.sentences(settings).toSet()
        for (lang in Language.entries) for (h in 0..23) for (proactive in listOf(true, false)) {
            val text = ResponseRenderer().text(SpeechSpec.DeliveryDirections(0, proactive), lang, frozen, LocalTimeHm(h, 0))
            SentenceSplitter.split(text).forEach { assertTrue("$lang $it", SpokenSentence(lang, it) in all) }
        }
    }
    @Test fun includesSubstitutedStepsGatesRepliesAndPhrases() {
        assertTrue(has(Language.EN, "Go to the blue gate."))
        assertTrue(has(Language.HI, "the blue gate पर जाइए।"))
        assertTrue(has(Language.EN, "Gate 1 is at Press Enclave Road, near Punjab National Bank."))
        assertTrue(has(Language.EN, "Gate 3 is at colony door no 3, opposite Aurobindo College."))
        assertTrue(has(Language.EN, "The night window is from 22:30 to 05:30."))
        assertTrue(has(Language.EN, "Please wait"))
        assertTrue(has(Language.HI, "कृपया प्रतीक्षा करें"))
        assertTrue(has(Language.EN, "This is Agent."))
        assertTrue(has(Language.EN, "This call is being recorded."))
    }
    @Test fun mapLinkIsNeverSpoken() {
        val link = settings.copy(mapLocation = "https://maps.example/x")
        assertFalse(SpokenTextCatalog.sentences(link).any { "https://" in it.text })
    }
}
