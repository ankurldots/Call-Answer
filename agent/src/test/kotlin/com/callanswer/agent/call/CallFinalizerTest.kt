package com.callanswer.agent.call

import com.callanswer.agent.Clock
import com.callanswer.agent.obs.CallTrace
import com.callanswer.agent.obs.TraceEventName
import com.callanswer.agent.store.CostCounter
import com.callanswer.agent.store.CostNotifier
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.RecordingSigner
import com.callanswer.agent.store.RecordingStore
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.core.engine.CallSession
import com.callanswer.core.engine.ConversationState
import com.callanswer.core.model.CostRates
import com.callanswer.core.model.EndReason
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import com.callanswer.core.model.Outcome
import com.callanswer.core.model.Speaker
import com.callanswer.core.model.Utterance
import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId

private class TestClock(var now: Long) : Clock { override fun nowMillis() = now }

class CallFinalizerTest : FunSpec({
    val user = UserId("alice-1")
    val start = 1_750_000_000_000L
    val noNotify = object : CostNotifier {
        override suspend fun limit80Reached(totalPaise: Long) {}
        override suspend fun limit100Reached(totalPaise: Long) {}
    }

    fun setup(): Triple<UserScopedRepository, InMemoryBlobStore, CallFinalizer> {
        val items = InMemoryItemStore(); val blobs = InMemoryBlobStore()
        val repo = UserScopedRepository(user, items, blobs, pause = {})
        val fin = CallFinalizer(user, repo, CostCounter(repo, noNotify), RecordingStore(blobs, RecordingSigner { k, _ -> "u://$k" }))
        return Triple(repo, blobs, fin)
    }

    fun input(trace: CallTrace, state: ConversationState, wav: ByteArray?) = FinalizeInput(
        session = CallSession("c1", null, Instant.ofEpochMilli(start), ZoneId.of("Asia/Kolkata"), FrozenSettings()),
        state = state, trace = trace, endedAtMs = 60_000, outcome = Outcome.DELIVERY_DIRECTIONS_GIVEN,
        endReason = EndReason.DELIVERY_DONE, rates = CostRates(exotelPerMinutePaise = 60),
        monthlyLimitInr = null, replyLatenciesMs = listOf(900, 500, 700, 1500), toolGateNumbers = setOf("3"),
        wavRecording = wav,
    )

    test("stores entry with latency, cost, recording key, trace and sends the end event") {
        val (repo, blobs, _) = setup()
        val clock = TestClock(start)
        val trace = CallTrace(user.sub, "c1", clock)
        clock.now = start + 800; trace.record(TraceEventName.FIRST_AGENT_AUDIO, "sonic")
        var sent: String? = null
        val fin = CallFinalizer(user, repo, CostCounter(repo, noNotify), RecordingStore(blobs, RecordingSigner { k, _ -> k }),
            { _, id, _, _ -> sent = id })
        val state = ConversationState(transcript = listOf(Utterance(Speaker.AGENT, "Please use Gate 3", Language.EN, 0, 10)))
        val r = fin.finalize(input(trace, state, byteArrayOf(1, 2)))
        r.stored shouldBe true
        r.gateMismatches shouldBe emptyList()
        r.entry.latency?.ttfaMs shouldBe 800
        r.entry.latency?.medianReplyMs shouldBe 800
        r.entry.latency?.maxReplyMs shouldBe 1500
        r.entry.costEstimatePaise shouldBe 60
        r.entry.recordingKey shouldBe Keys.recording(user, "c1")
        blobs.get(Keys.recording(user, "c1"))?.size shouldBe 2
        repo.getCall(start, "c1")?.id shouldBe "c1"
        repo.getTrace("c1")!!.contains("CALL_END") shouldBe true
        sent shouldBe "c1"
    }

    test("a gate number not in the tool result is reported") {
        val (_, _, fin) = setup()
        val trace = CallTrace(user.sub, "c1", TestClock(start))
        val state = ConversationState(transcript = listOf(Utterance(Speaker.AGENT, "Gate 3 or gate number 7", Language.EN, 0, 10)))
        val r = fin.finalize(input(trace, state, null))
        r.gateMismatches shouldBe listOf("7")
        trace.events().map { it.errorCode } shouldContain "GATE_NUMBER_MISMATCH"
        r.entry.recordingKey shouldBe null
    }

    context("recent calls") {
        val num = "+919812345678"
        fun run(
            intent: IntentCategory, number: String?, outcome: Outcome = Outcome.DELIVERY_DIRECTIONS_GIVEN,
            pushes: MutableList<String>,
        ): suspend () -> Pair<UserScopedRepository, FinalizeResult> = {
            val items = InMemoryItemStore(); val blobs = InMemoryBlobStore()
            val repo = UserScopedRepository(user, items, blobs, pause = {})
            val fin = CallFinalizer(
                user, repo, CostCounter(repo, noNotify), RecordingStore(blobs, RecordingSigner { k, _ -> k }),
                recentNotifier = { _, n, end, i, id -> pushes += "$n|$end|${i.name}|$id" },
                recentRetryPause = {},
            )
            val trace = CallTrace(user.sub, "c1", TestClock(start))
            val base = input(trace, ConversationState(intent = intent), null)
            val inp = FinalizeInput(
                session = CallSession("c1", number, Instant.ofEpochMilli(start), ZoneId.of("Asia/Kolkata"), FrozenSettings()),
                state = base.state, trace = trace, endedAtMs = 60_000, outcome = outcome, endReason = base.endReason,
                rates = base.rates, monthlyLimitInr = null,
            )
            repo to fin.finalize(inp)
        }
        test("an Agent-handled delivery call is recorded and pushed") {
            val pushes = mutableListOf<String>()
            val (repo, _) = run(IntentCategory.DELIVERY, num, pushes = pushes)()
            repo.lastNonSolicitation(E164(num), start + 60_000, 30)?.intent shouldBe IntentCategory.DELIVERY
            pushes shouldBe listOf("$num|${start + 60_000}|DELIVERY|c1")
        }
        test("SOLICITATION, hidden numbers and ring-through calls write and push nothing") {
            val pushes = mutableListOf<String>()
            val (r1, _) = run(IntentCategory.SOLICITATION, num, pushes = pushes)()
            r1.lastNonSolicitation(E164(num), start + 60_000, 30) shouldBe null
            run(IntentCategory.DELIVERY, null, pushes = pushes)()
            val (r3, _) = run(IntentCategory.DELIVERY, num, Outcome.RING_THROUGH_ANSWERED, pushes = pushes)()
            r3.lastNonSolicitation(E164(num), start + 60_000, 30) shouldBe null
            pushes shouldBe emptyList()
        }
    }
})
