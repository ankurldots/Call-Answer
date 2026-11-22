package com.callanswer.core.engine

import com.callanswer.core.delivery.DeliveryDirectionsRenderer
import com.callanswer.core.delivery.StepTranslator
import com.callanswer.core.gate.GateInfoRenderer
import com.callanswer.core.gate.selectGate
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.Language
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.stalling.FictitiousGate
import java.util.concurrent.atomic.AtomicLong

/** Text of one spoken response and its unique id. */
data class RenderedResponse(val responseId: String, val text: String)

fun interface ResponseIdGenerator {
    fun next(): String
}

/** Process-unique ids: `<prefix>-<n>`. A fresh prefix per call keeps ids unique across calls. */
class SequentialResponseIdGenerator(private val prefix: String = "r") : ResponseIdGenerator {
    private val counter = AtomicLong(0)
    override fun next(): String = "$prefix-${counter.incrementAndGet()}"
}

/**
 * Turns a [SpeechSpec] into Hindi or English text. Every call to [render] returns a new responseId.
 * [SpeechSpec.Stall] text comes from the stalling responder and is passed through.
 */
class ResponseRenderer(
    private val ids: ResponseIdGenerator = SequentialResponseIdGenerator(),
    private val translator: StepTranslator? = null,
) {
    /** [localTime] is the clock reading for the gate choice; null means unreadable (both gates). */
    fun render(
        spec: SpeechSpec,
        language: Language,
        frozen: FrozenSettings,
        localTime: LocalTimeHm?,
        seed: Long = 0L,
    ): RenderedResponse = RenderedResponse(ids.next(), text(spec, language, frozen, localTime, seed))

    fun text(
        spec: SpeechSpec,
        language: Language,
        frozen: FrozenSettings,
        localTime: LocalTimeHm?,
        seed: Long = 0L,
    ): String {
        val hi = language == Language.HI
        fun t(en: String, hindi: String) = if (hi) hindi else en
        return when (spec) {
            SpeechSpec.Greeting -> t("Hello", "हैलो")
            SpeechSpec.Disclosure -> t(
                "This call is being recorded and handled by an automated assistant.",
                "यह कॉल रिकॉर्ड की जा रही है और एक स्वचालित सहायक द्वारा संभाली जा रही है।",
            )
            is SpeechSpec.DeliveryDirections -> {
                val gateText = GateInfoRenderer.render(selectGate(localTime, frozen.gates), language, includeWindowTimes = false)
                val parts = DeliveryDirectionsRenderer.render(frozen.deliverySteps, language, frozen.mapLocation, gateText, translator)
                (if (spec.proactiveGate) parts else parts.drop(1)).joinToString(" ")
            }
            is SpeechSpec.GateInfo ->
                if (spec.fictitious) FictitiousGate.answer(frozen.gates, language, seed)
                else GateInfoRenderer.render(selectGate(localTime, frozen.gates), language, includeWindowTimes = spec.inResponseToQuery)
            is SpeechSpec.LocationSmsSent -> if (spec.addressConfirmed) {
                t(
                    "Yes, that address is correct. I am sending the location to you by text message.",
                    "जी, यह पता सही है। मैं आपको लोकेशन टेक्स्ट मैसेज से भेज रहा हूँ।",
                )
            } else {
                t(
                    "I am sending the location to you by text message.",
                    "मैं आपको लोकेशन टेक्स्ट मैसेज से भेज रहा हूँ।",
                )
            }
            SpeechSpec.AddressNotMatching -> t(
                "Sorry, that address does not match. Please check the address and call again.",
                "माफ़ कीजिए, यह पता मेल नहीं खाता। कृपया पता जाँचकर दोबारा फ़ोन कीजिए।",
            )
            SpeechSpec.AskReason -> t("How can I help you? Why are you calling?", "मैं आपकी क्या मदद कर सकता हूँ? आप किसलिए फ़ोन कर रहे हैं?")
            SpeechSpec.AskRepeatRequest -> t("Sorry, I could not hear you. Please say that again.", "माफ़ कीजिए, मैं सुन नहीं पाया। कृपया दोबारा बोलिए।")
            SpeechSpec.TransferNotice -> t("Please hold on while I connect you.", "कृपया रुकिए, मैं आपको जोड़ रहा हूँ।")
            SpeechSpec.PleaseWait -> t("Please wait.", "कृपया प्रतीक्षा कीजिए।")
            SpeechSpec.NobodyAvailable -> t("Nobody is available right now. Please call again shortly.", "अभी कोई उपलब्ध नहीं है। कृपया थोड़ी देर बाद फिर से फ़ोन कीजिए।")
            SpeechSpec.WillReturnCall -> t("We will call you back. Thank you.", "हम आपको वापस फ़ोन करेंगे। धन्यवाद।")
            SpeechSpec.NoFurtherRepetitions -> t("I cannot repeat the directions again. Thank you, goodbye.", "मैं दिशा-निर्देश दोबारा नहीं दोहरा सकता। धन्यवाद, नमस्ते।")
            is SpeechSpec.Stall -> spec.callerText
            SpeechSpec.StallFiller -> t("Hmm, one moment please.", "हम्म, एक मिनट रुकिए।")
            SpeechSpec.SilencePrompt -> t("Hello, are you there?", "हैलो, क्या आप सुन रहे हैं?")
            SpeechSpec.BridgingPhrase -> t("One moment.", "एक मिनट।")
            is SpeechSpec.QuickReply -> spec.text
            SpeechSpec.FallbackPhrase -> t("One moment, please.", "कृपया एक क्षण रुकिए।")
            SpeechSpec.PoliteGoodbye -> t("Thank you. Goodbye.", "धन्यवाद। नमस्ते।")
            SpeechSpec.AbuseGoodbye -> t(
                "Abusive language is not accepted. This call is being ended.",
                "गाली-गलौज स्वीकार नहीं है। यह कॉल समाप्त की जा रही है।",
            )
        }
    }
}
