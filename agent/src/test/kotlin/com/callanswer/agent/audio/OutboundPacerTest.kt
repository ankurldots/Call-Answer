package com.callanswer.agent.audio

import com.callanswer.agent.Clock
import com.callanswer.agent.exotel.ExotelEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OutboundPacerTest : FunSpec({
    class ManualClock(var now: Long = 0) : Clock {
        override fun nowMillis() = now
    }

    fun setup(chunk: Int = 3200, ahead: Int = 300): Triple<OutboundPacer, ManualClock, MutableList<ExotelEvent>> {
        val clock = ManualClock()
        val events = mutableListOf<ExotelEvent>()
        return Triple(OutboundPacer("s1", StreamFormat(), clock, { events.add(it) }, chunk, ahead), clock, events)
    }

    test("pads last chunk, sends mark after utterance, respects send-ahead") {
        val (p, clock, events) = setup()
        p.offer("c1", ByteArray(8000))
        p.endUtterance("c1", "m1")
        p.pump() shouldBe 100L
        events.size shouldBe 1
        clock.now = 100
        p.pump() shouldBe 200L
        events.size shouldBe 2
        clock.now = 300
        p.pump() shouldBe null // third chunk (padded) and mark
        events.size shouldBe 4
        events.filterIsInstance<ExotelEvent.Media>().all { PcmCodec.decodeBase64Bytes(it.payload)!!.size == 3200 } shouldBe true
        (events.last() as ExotelEvent.Mark).name shouldBe "m1"
    }

    test("interrupt sends exactly one clear, drops queue and later audio until end") {
        val (p, _, events) = setup()
        p.offer("c1", ByteArray(9600))
        p.pump()
        p.interrupt() shouldBe true
        p.interrupt() shouldBe false
        p.offer("c1", ByteArray(3200))
        p.endUtterance("c1")
        p.pump() shouldBe null
        events.count { it is ExotelEvent.Clear } shouldBe 1
        events.count { it is ExotelEvent.Media } shouldBe 1
        p.offer("c2", ByteArray(3200))
        p.pump()
        events.count { it is ExotelEvent.Media } shouldBe 2
    }

    test("small chunks configuration") {
        val (p, _, events) = setup(chunk = 320, ahead = 100)
        p.offer("c", ByteArray(6400))
        p.pump()
        events.size shouldBe 5 // 100 ms ahead = 5 chunks of 20 ms
    }
})
