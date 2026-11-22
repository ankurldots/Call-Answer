package com.callanswer.agent.audio

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SilenceDetectorTest : FunSpec({
    fun pcm(amplitude: Int, ms: Int, rate: Int = 16_000): ByteArray {
        val n = rate * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = if (i % 2 == 0) amplitude else -amplitude
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    test("silence is not counted before the mark") {
        val d = SilenceDetector()
        d.onCallerAudio(pcm(0, 100), 100)
        d.silentMs(10_000) shouldBe 0
        d.isTimedOut(10_000, 8_000) shouldBe false
    }

    test("silence counts from the mark and times out") {
        val d = SilenceDetector()
        d.onAgentAudioPlayed(1_000)
        d.onCallerAudio(pcm(10, 100), 2_000)
        d.silentMs(5_000) shouldBe 4_000
        d.isTimedOut(8_999, 8_000) shouldBe false
        d.isTimedOut(9_000, 8_000) shouldBe true
    }

    test("caller speech restarts the silence clock") {
        val d = SilenceDetector()
        d.onAgentAudioPlayed(0)
        d.onCallerAudio(pcm(5_000, 40), 6_000) shouldBe true
        d.isTimedOut(13_000, 8_000) shouldBe false
        d.isTimedOut(14_000, 8_000) shouldBe true
    }

    test("new agent audio stops counting until the next mark") {
        val d = SilenceDetector()
        d.onAgentAudioPlayed(0)
        d.onAgentAudioStarted()
        d.isTimedOut(60_000, 8_000) shouldBe false
        d.onAgentAudioPlayed(60_000)
        d.isTimedOut(68_000, 8_000) shouldBe true
    }

    test("restart begins a new window after a re-prompt") {
        val d = SilenceDetector()
        d.onAgentAudioPlayed(0)
        d.restart(9_000)
        d.isTimedOut(16_999, 8_000) shouldBe false
        d.isTimedOut(17_000, 8_000) shouldBe true
    }

    test("frames split across calls are still detected") {
        val d = SilenceDetector()
        val loud = pcm(5_000, 20)
        d.onCallerAudio(loud.copyOfRange(0, 100), 0) shouldBe false
        d.onCallerAudio(loud.copyOfRange(100, loud.size), 0) shouldBe true
    }
})
