package com.callanswer.agent.exotel

import com.callanswer.agent.audio.FrameValidator
import com.callanswer.agent.audio.PcmCodec
import com.callanswer.agent.audio.StreamFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ExotelMessagesTest : FunSpec({
    test("decodes documented start event with string sample rate") {
        val text = """{"event":"start","sequence_number":"1","stream_sid":"s1","start":{"stream_sid":"s1",
            "call_sid":"c1","from":"+911","to":"+912","media_format":{"encoding":"base64","sample_rate":"16000","bit_rate":"128kbps"}}}"""
        val e = ExotelMessages.decode(text) as ExotelEvent.Start
        e.callSid shouldBe "c1"
        e.from shouldBe "+911"
        e.format.sampleRate shouldBe 16000
        e.sequenceNumber shouldBe 1L
    }

    test("round trips every event type") {
        val events = listOf(
            ExotelEvent.Connected,
            ExotelEvent.Start("s", "c", "f", "t", StreamFormat(sampleRate = 24000, frameBytes = 640), 1),
            ExotelEvent.Media("s", PcmCodec.encodeBase64(shortArrayOf(1, -2, 300)), 5, "20", 2),
            ExotelEvent.Mark("s", "m1", 3),
            ExotelEvent.Clear("s"),
            ExotelEvent.Dtmf("s", "5", "100", 4),
            ExotelEvent.Stop("s", "c", "done", 6),
        )
        for (e in events) ExotelMessages.decode(ExotelMessages.encode(e)) shouldBe e
    }

    test("malformed and unknown messages decode to null") {
        ExotelMessages.decode("not json") shouldBe null
        ExotelMessages.decode("""{"event":"weird"}""") shouldBe null
    }

    test("PCM base64 round trip") {
        val s = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE)
        PcmCodec.decodeBase64(PcmCodec.encodeBase64(s))!!.toList() shouldBe s.toList()
    }

    test("mismatched frames are discarded and counted without affecting later frames") {
        val v = FrameValidator(StreamFormat(frameBytes = 4))
        v.accept(PcmCodec.encodeBase64(shortArrayOf(1))) shouldBe null // wrong length
        v.accept("***") shouldBe null // bad base64
        v.accept(PcmCodec.encodeBase64(shortArrayOf(1, 2)), "mulaw") shouldBe null // wrong encoding
        v.accept(PcmCodec.encodeBase64(shortArrayOf(1, 2)))!!.toList() shouldBe listOf<Short>(1, 2)
        v.discardedCount shouldBe 3
    }
})
