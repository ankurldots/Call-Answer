package com.callanswer.core.cloud

import com.callanswer.core.cloud.CallPolicy.Effect
import com.callanswer.core.cloud.CallPolicy.EndReason
import com.callanswer.core.cloud.CallPolicy.Event
import com.callanswer.core.cloud.CallPolicy.Intent
import com.callanswer.core.cloud.CallPolicy.Phase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class CallPolicyTest : StringSpec({
    fun run(events: List<Pair<Event, Long>>, config: CallPolicy.Config = CallPolicy.Config()): CallPolicy.Result {
        var r = CallPolicy.Result(CallPolicy.initial(config), emptyList())
        for ((e, t) in events) r = CallPolicy.step(r.state, e, t)
        return r
    }
    val start = Event.StreamStarted to 0L

    "greeting is repeated once, second silence ends the call" {
        val r = run(listOf(start, Event.SilenceElapsed to 8_000, Event.SilenceElapsed to 16_000))
        r.state.greetingRepeats shouldBe 1
        r.state.phase shouldBe Phase.CLOSING
        r.state.endReason shouldBe EndReason.CALLER_SILENCE
    }

    "silence outside stalling: one prompt then end" {
        val r = run(listOf(start, Event.CallerUtterance("hi") to 1, Event.SilenceElapsed to 2, Event.SilenceElapsed to 3))
        r.state.endReason shouldBe EndReason.CALLER_SILENCE
        r.effects shouldContain Effect.SendAlert("CALLER_SILENT")
    }

    "stalling ends at third silence" {
        val r = run(listOf(start, Event.IntentLogged(Intent.SOLICITATION) to 1,
            Event.SilenceElapsed to 2, Event.SilenceElapsed to 3))
        r.state.phase shouldBe Phase.STALLING
        CallPolicy.step(r.state, Event.SilenceElapsed, 4).state.endReason shouldBe EndReason.CALLER_SILENCE
    }

    "max stalling duration ends stalling call" {
        val r = run(listOf(start, Event.IntentLogged(Intent.SOLICITATION) to 1_000, Event.AgentAudioDone to 5 * 60_000L + 1_000))
        r.state.endReason shouldBe EndReason.MAX_STALLING
    }

    "15 minute limit applies outside stalling, 60 minute limit applies to stalling" {
        run(listOf(start, Event.AgentAudioDone to MAX_15)).state.endReason shouldBe EndReason.MAX_DURATION
        val cfg = CallPolicy.Config(maxStallingMinutes = 60)
        val r = run(listOf(start, Event.IntentLogged(Intent.SOLICITATION) to 1, Event.AgentAudioDone to MAX_15 + 1), cfg)
        r.state.phase shouldBe Phase.STALLING
        run(listOf(start, Event.IntentLogged(Intent.SOLICITATION) to 1, Event.AgentAudioDone to 60 * 60_000L), cfg)
            .state.endReason shouldBe EndReason.HARD_LIMIT
    }

    "unknown intent: two clarifications then message request; gate query not counted" {
        val r = run(listOf(start, Event.IntentLogged(Intent.UNKNOWN) to 1, Event.ToolCalled("get_gate_info") to 2,
            Event.IntentLogged(Intent.UNKNOWN) to 3))
        r.state.clarifyPrompts shouldBe 2
        r.state.messageRequested shouldBe false
        val r3 = CallPolicy.step(r.state, Event.IntentLogged(Intent.UNKNOWN), 4)
        r3.state.messageRequested shouldBe true
        CallPolicy.step(r3.state, Event.CallerUtterance("Ravi, parcel"), 5).state.endReason shouldBe EndReason.UNKNOWN_MESSAGE
    }

    "garbled speech: three repeats then end" {
        val r = run(listOf(start) + (1..4).map { Event.CallerUtterance(null) to it.toLong() })
        r.state.garbledRepeats shouldBe 3
        r.state.endReason shouldBe EndReason.GARBLED
    }

    "directions: three repetitions allowed, fifth request ends the call" {
        val calls = (1..5).map { Event.ToolCalled(CallPolicy.TOOL_DIRECTIONS) to it.toLong() }
        val r = run(listOf(start) + calls.take(4))
        r.state.directionsRepetitions shouldBe 3
        run(listOf(start) + calls).state.endReason shouldBe EndReason.DIRECTIONS_LIMIT
    }

    "closing completes after the farewell audio" {
        val r = run(listOf(start, Event.SilenceElapsed to 1, Event.SilenceElapsed to 2, Event.AgentAudioDone to 3))
        r.state.phase shouldBe Phase.ENDED
        r.effects shouldContain Effect.CloseStream(EndReason.CALLER_SILENCE)
    }
}) {
    companion object { const val MAX_15 = 15 * 60_000L }
}
