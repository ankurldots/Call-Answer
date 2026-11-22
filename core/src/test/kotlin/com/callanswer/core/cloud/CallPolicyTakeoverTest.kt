package com.callanswer.core.cloud

import com.callanswer.core.cloud.CallPolicy.Effect
import com.callanswer.core.cloud.CallPolicy.Event
import com.callanswer.core.cloud.CallPolicy.Phase
import com.callanswer.core.cloud.CallPolicy.TimerKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class CallPolicyTakeoverTest : StringSpec({
    fun run(vararg events: Pair<Event, Long>): CallPolicy.Result {
        var r = CallPolicy.Result(CallPolicy.initial(), emptyList())
        for ((e, t) in listOf<Pair<Event, Long>>(Event.StreamStarted to 0L) + events) r = CallPolicy.step(r.state, e, t)
        return r
    }

    "request starts the alert, the 15 s wait timer and the 30 s nobody timer" {
        val r = run(Event.TakeoverRequested to 1_000)
        r.state.phase shouldBe Phase.WAITING_TAKEOVER
        r.effects shouldContain Effect.SendAlert("TAKEOVER_REQUEST")
        r.effects shouldContain Effect.StartTimer(TimerKind.TAKEOVER_WAIT, 15_000)
        r.effects shouldContain Effect.StartTimer(TimerKind.TAKEOVER_NOBODY, 30_000)
    }

    "please wait repeats every 15 seconds while waiting" {
        val r = run(Event.TakeoverRequested to 0, Event.TimerFired(TimerKind.TAKEOVER_WAIT) to 15_000)
        r.effects shouldContain Effect.Inject("PLEASE_WAIT")
        r.effects shouldContain Effect.StartTimer(TimerKind.TAKEOVER_WAIT, 15_000)
    }

    "silence does not end the call while waiting" {
        val r = run(Event.TakeoverRequested to 0, Event.SilenceElapsed to 8_000, Event.SilenceElapsed to 16_000)
        r.state.phase shouldBe Phase.WAITING_TAKEOVER
    }

    "after 30 seconds the agent says nobody is available and stays on the call" {
        val r = run(Event.TakeoverRequested to 0, Event.TimerFired(TimerKind.TAKEOVER_NOBODY) to 30_000)
        r.effects shouldContain Effect.Inject("NOBODY_AVAILABLE")
        r.effects shouldNotContain Effect.CloseStream(CallPolicy.EndReason.CALLER_SILENCE)
        r.state.phase shouldBe Phase.LISTENING
        // a stale wait timer does nothing afterwards
        CallPolicy.step(r.state, Event.TimerFired(TimerKind.TAKEOVER_WAIT), 45_000).effects shouldBe emptyList()
    }

    "second tap while a bridge is in progress is ignored" {
        val r1 = run(Event.TakeoverRequested to 0, Event.UserTookOver to 5_000)
        r1.state.bridgeInProgress shouldBe true
        r1.effects shouldContain Effect.ClearAudio
        val r2 = CallPolicy.step(r1.state, Event.UserTookOver, 6_000)
        r2.effects shouldBe emptyList()
        r2.state shouldBe r1.state
    }

    "second takeover request while waiting or bridging is ignored" {
        val w = run(Event.TakeoverRequested to 0)
        CallPolicy.step(w.state, Event.TakeoverRequested, 1).effects shouldBe emptyList()
        val b = run(Event.TakeoverRequested to 0, Event.UserTookOver to 1)
        CallPolicy.step(b.state, Event.TakeoverRequested, 2).effects shouldBe emptyList()
    }

    "bridge connected closes Sonic and never opens a replacement" {
        val r = run(Event.TakeoverRequested to 0, Event.UserTookOver to 1, Event.BridgeConnected to 3_000)
        r.state.phase shouldBe Phase.TAKEOVER
        r.effects shouldContain Effect.CloseSonicSession
        CallPolicy.step(r.state, Event.SonicFailed, 4_000).effects shouldBe emptyList()
    }

    "failed bridge returns to the agent and records the failure" {
        val r = run(Event.TakeoverRequested to 0, Event.UserTookOver to 1, Event.BridgeFailed to 5_000)
        r.state.phase shouldBe Phase.LISTENING
        r.state.bridgeInProgress shouldBe false
        r.effects shouldContain Effect.Record("BRIDGE_FAILED")
        r.effects shouldContain Effect.Inject("PERSON_UNREACHABLE")
    }

    "dial timeout counts as a failed bridge" {
        val r = run(Event.UserTookOver to 1, Event.TimerFired(TimerKind.BRIDGE_DIAL) to 16_000)
        r.state.phase shouldBe Phase.LISTENING
        r.effects shouldContain Effect.Record("BRIDGE_FAILED")
    }
})
