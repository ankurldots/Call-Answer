package com.callanswer.core.engine

import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class BaseConversationEngineTest : FunSpec({
    val engine = BaseConversationEngine()
    fun ctx(disclosure: Boolean = false) = EngineContext(FrozenSettings(disclosureEnabled = disclosure), true)
    fun utter(text: String) = ConversationEvent.CallerUtterance(1000, text, null, null)
    fun specs(t: Transition) = t.effects.filterIsInstance<Effect.Speak>().map { it.spec }

    test("greeting only when disclosure is off") {
        val t = engine.reduce(ConversationState(), ConversationEvent.CallAnswered(0), ctx())
        specs(t) shouldBe listOf(SpeechSpec.Greeting)
        t.state.phase shouldBe Phase.GREETING
    }

    test("greeting then disclosure when on, and not repeated") {
        val t = engine.reduce(ConversationState(), ConversationEvent.CallAnswered(0), ctx(true))
        specs(t) shouldBe listOf(SpeechSpec.Greeting, SpeechSpec.Disclosure)
        specs(engine.reduce(t.state, ConversationEvent.CallAnswered(10), ctx(true))) shouldBe emptyList()
    }

    test("delivery utterance gives directions and appends transcript") {
        val t = engine.reduce(ConversationState(phase = Phase.GREETING), utter("I have a parcel delivery"), ctx())
        specs(t).single().shouldBeInstanceOf<SpeechSpec.DeliveryDirections>()
        t.state.intent shouldBe IntentCategory.DELIVERY
        t.state.transcript shouldHaveSize 1
    }

    test("unknown keeps the current category and asks the reason") {
        val s = ConversationState(phase = Phase.LISTENING, intent = IntentCategory.DELIVERY)
        val t = engine.reduce(s, utter("hmm okay"), ctx())
        t.state.intent shouldBe IntentCategory.DELIVERY
        specs(t) shouldBe listOf(SpeechSpec.AskReason)
        t.state.clarifyPrompts shouldBe 1
    }

    test("empty transcript asks once to repeat") {
        val t = engine.reduce(ConversationState(phase = Phase.GREETING), utter("  "), ctx())
        specs(t) shouldBe listOf(SpeechSpec.AskRepeatRequest)
        t.state.clarifyPrompts shouldBe 0
        t.state.transcript shouldHaveSize 0
    }

    test("every response gets a distinct id and finished ids are recorded") {
        val a = engine.reduce(ConversationState(), ConversationEvent.CallAnswered(0), ctx(true))
        val ids = a.effects.filterIsInstance<Effect.Speak>().map { it.responseId }
        ids.toSet() shouldHaveSize 2
        val b = engine.reduce(a.state, ConversationEvent.AgentSpeechFinished(5, ids[0]), ctx())
        b.state.spokenResponseIds shouldBe setOf(ids[0])
    }

    test("language follows the utterance, then the last language") {
        val t = engine.reduce(ConversationState(phase = Phase.GREETING), utter("aap kaun bol rahe hain"), ctx())
        t.effects.filterIsInstance<Effect.Speak>().first().language shouldBe Language.HI
        t.state.lastLanguage shouldBe Language.HI
    }

    // ---- Location URL and address ----
    fun locCtx(url: String? = "https://maps.app.goo.gl/x", address: String? = "Flat 4 Green Park Apartments", known: Boolean = true) =
        EngineContext(FrozenSettings(mapLocation = url, address = address), known, callerNumber = "+919811111111")
    val delivering = ConversationState(phase = Phase.LISTENING, intent = IntentCategory.DELIVERY)

    test("no location text is sent when the directions end") {
        val directions = engine.reduce(ConversationState(phase = Phase.GREETING), utter("I have a parcel delivery"), locCtx())
        directions.effects.filterIsInstance<Effect.SendMapSms>() shouldHaveSize 0
        val id = directions.effects.filterIsInstance<Effect.Speak>().single().responseId
        val done = engine.reduce(directions.state, ConversationEvent.AgentSpeechFinished(2000, id), locCtx())
        val end = engine.reduce(done.state, utter("ok thanks"), locCtx())
        end.effects.filterIsInstance<Effect.SendMapSms>() shouldHaveSize 0
        end.effects.filterIsInstance<Effect.Notify>() shouldHaveSize 0
    }
    test("a matching stated address is confirmed and the location is texted") {
        val t = engine.reduce(delivering, utter("Sir address is flat 4 green park"), locCtx())
        specs(t) shouldBe listOf(SpeechSpec.LocationSmsSent(addressConfirmed = true))
        t.effects.filterIsInstance<Effect.SendMapSms>().single().number shouldBe "+919811111111"
        t.state.locationSent shouldBe true
    }
    test("a mismatching stated address sends nothing and does not speak the address") {
        val t = engine.reduce(delivering, utter("Sir address is B 12 Lotus Heights"), locCtx())
        specs(t) shouldBe listOf(SpeechSpec.AddressNotMatching)
        t.effects.filterIsInstance<Effect.SendMapSms>() shouldHaveSize 0
    }
    test("a request for the location texts it once") {
        val first = engine.reduce(delivering, utter("sir I cannot find the house, send location"), locCtx())
        specs(first) shouldBe listOf(SpeechSpec.LocationSmsSent(addressConfirmed = false))
        first.effects.filterIsInstance<Effect.SendMapSms>() shouldHaveSize 1
        val again = engine.reduce(first.state, utter("where is the house"), locCtx())
        again.effects.filterIsInstance<Effect.SendMapSms>() shouldHaveSize 0
    }
    test("without a Location URL the directions are spoken and the User is told at the end") {
        val t = engine.reduce(delivering, utter("send me the location"), locCtx(url = null))
        specs(t).single().shouldBeInstanceOf<SpeechSpec.DeliveryDirections>()
        t.state.locationWanted shouldBe true
    }
    // ---- Abusive language ----
    test("abusive caller is told and the call ends, even during delivery") {
        for (s in listOf(ConversationState(phase = Phase.GREETING), ConversationState(phase = Phase.DELIVERY, intent = IntentCategory.DELIVERY))) {
            val t = engine.reduce(s, utter("parcel hai, chutiya"), ctx())
            specs(t) shouldBe listOf(SpeechSpec.AbuseGoodbye)
            t.effects.last() shouldBe Effect.EndCall(com.callanswer.core.model.EndReason.ABUSIVE_LANGUAGE)
            t.state.phase shouldBe Phase.ENDED
            t.state.transcript.last().text shouldBe "parcel hai, chutiya"
        }
    }
    test("abuse is ignored while the User has taken over or muted the Agent") {
        for (mode in listOf(Mode.TAKE_OVER, Mode.AGENT_MUTED)) {
            val t = engine.reduce(ConversationState(phase = Phase.LISTENING, mode = mode), utter("madarchod"), ctx())
            t.effects shouldBe emptyList()
            t.state.phase shouldBe Phase.LISTENING
        }
    }

    test("tick without audio for over 10 s ends the call as interrupted") {
        val t = engine.reduce(ConversationState(phase = Phase.GREETING), ConversationEvent.Tick(20_000, 1_000, 10_500), ctx())
        t.effects.filterIsInstance<Effect.EndCall>().single().reason shouldBe com.callanswer.core.model.EndReason.AUDIO_INTERRUPTED
    }

    test("tick that reports no audio count (capture unavailable) re-greets on the silence timer instead of interrupting") {
        val long = FrozenSettings().silenceTimeoutSeconds * 1000L + 1
        val t = engine.reduce(ConversationState(phase = Phase.GREETING), ConversationEvent.Tick(20_000, long, 0), ctx())
        t.effects.filterIsInstance<Effect.EndCall>() shouldBe emptyList()
        specs(t) shouldBe listOf(SpeechSpec.Greeting)
    }
})
