package com.callanswer.agent.audio

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private fun tone(rate: Int, hz: Double, n: Int, amp: Double = 10000.0) =
    ShortArray(n) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }

class ResamplerTest : FunSpec({
    test("8 to 16 kHz keeps length and tone") {
        val out = Resampler.resample(tone(8000, 440.0, 800), 8000, 16000)
        out.size shouldBe 1600
        val ref = tone(16000, 440.0, 1600)
        for (i in 200 until 1400) (abs(out[i] - ref[i]) < 400) shouldBe true
    }

    test("24 to 8 kHz keeps length") {
        Resampler.resample(tone(24000, 300.0, 2400), 24000, 8000).size shouldBe 800
    }

    test("full-scale input stays in 16-bit range") {
        val loud = ShortArray(400) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val out = Resampler.resample(loud, 8000, 16000)
        out.size shouldBe 800
    }

    test("streaming in chunks equals one-shot") {
        val x = tone(8000, 500.0, 1000)
        val r = Resampler(8000, 16000)
        val parts = x.toList().chunked(160).flatMap { r.process(it.toShortArray()).toList() } + r.flush().toList()
        parts shouldBe Resampler.resample(x, 8000, 16000).toList()
    }
})
