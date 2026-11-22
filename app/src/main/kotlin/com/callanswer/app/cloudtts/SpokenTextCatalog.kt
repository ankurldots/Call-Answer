package com.callanswer.app.cloudtts
import com.callanswer.app.data.displayAgentName
import com.callanswer.app.tts.SentenceSplitter
import com.callanswer.app.tts.VoiceSamplePlayer
import com.callanswer.core.delivery.StepTranslator
import com.callanswer.core.engine.ResponseRenderer
import com.callanswer.core.engine.SpeechSpec
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.model.QuickReply
import com.callanswer.core.model.Settings
/** One sentence as a call will hand it to the synthesizer. */
data class SpokenSentence(val language: Language, val text: String)
/**
 * Every sentence a call can speak that is fixed by the settings: the Delivery_Directions (with and without the
 * gate statement), gate info for day, night and an unreadable clock (with and without night-window times), the
 * saved Quick_Replies, the fixed phrases and the Settings voice samples. Text comes from the same [ResponseRenderer] a call uses and is split
 * with [SentenceSplitter], so it matches the call's synthesizer input exactly.
 *
 * Not included, because they change per call: stalling replies, invented gate details for non-delivery callers,
 * and Quick_Replies typed during a call.
 */
object SpokenTextCatalog {
    private val fixedSpecs: List<SpeechSpec> = listOf(
        SpeechSpec.Greeting, SpeechSpec.Disclosure,
        SpeechSpec.LocationSmsSent(addressConfirmed = true), SpeechSpec.LocationSmsSent(addressConfirmed = false),
        SpeechSpec.AddressNotMatching, SpeechSpec.AskReason, SpeechSpec.AskRepeatRequest, SpeechSpec.TransferNotice,
        SpeechSpec.PleaseWait, SpeechSpec.NobodyAvailable, SpeechSpec.WillReturnCall, SpeechSpec.NoFurtherRepetitions,
        SpeechSpec.StallFiller, SpeechSpec.SilencePrompt, SpeechSpec.BridgingPhrase, SpeechSpec.FallbackPhrase,
        SpeechSpec.PoliteGoodbye, SpeechSpec.AbuseGoodbye,
    )
    fun sentences(settings: Settings, translator: StepTranslator? = null): List<SpokenSentence> {
        val frozen = settings.freeze()
        val renderer = ResponseRenderer(translator = translator)
        // One reading inside and one outside the night window, plus an unreadable clock (both gates).
        val w = frozen.gates.nightWindow
        val times: List<LocalTimeHm?> = listOf(w.start, w.end, null)
        val out = LinkedHashSet<SpokenSentence>()
        for (language in Language.entries) {
            fun add(text: String) = SentenceSplitter.split(text).forEach { out += SpokenSentence(language, it) }
            for (time in times) {
                for (proactive in listOf(true, false)) {
                    add(renderer.text(SpeechSpec.DeliveryDirections(repetition = 0, proactiveGate = proactive), language, frozen, time))
                }
                for (query in listOf(true, false)) {
                    add(renderer.text(SpeechSpec.GateInfo(inResponseToQuery = query), language, frozen, time))
                }
            }
            frozen.quickReplies.forEach { add(quickReplyText(it, language)) }
            fixedSpecs.forEach { add(renderer.text(it, language, frozen, null)) }
            // The Settings voice sample is played whole (not split), so it is cached under its exact text.
            out += SpokenSentence(language, VoiceSamplePlayer.sampleText(language, displayAgentName(settings.agentName)).trim())
        }
        return out.toList()
    }
    /** Same text the live-call chip sends ([com.callanswer.app.translate.textFor] without a translator). */
    private fun quickReplyText(r: QuickReply, language: Language): String {
        val own = if (language == Language.EN) r.textEn else r.textHi
        val other = if (language == Language.EN) r.textHi else r.textEn
        return own.ifBlank { other }
    }
}
