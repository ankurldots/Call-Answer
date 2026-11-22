package com.callanswer.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {
    private val loud = ShortArray(512) { 8000 }
    private val quiet = ShortArray(512)

    private fun run(gate: AgentWindowGate, voice: MutableList<Long>, out: MutableList<SpeechSegment>, pattern: String): SpeechSegmenter {
        val seg = SpeechSegmenter(EnergyVad(), gate, 0L, onVoiceActivity = { voice.add(it) }, onSegment = { out.add(it) })
        var t = 0L
        for (c in pattern) {
            seg.onFrame(PcmFrame(if (c == 'S') loud else quiet, t))
            t += 32
        }
        return seg
    }

    @Test
    fun endpointsAfterHangover() {
        val out = mutableListOf<SpeechSegment>()
        val voice = mutableListOf<Long>()
        run({ _, _ -> false }, voice, out, "S".repeat(5) + "_".repeat(13))
        assertEquals(1, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(160L, out[0].endMs)
        assertTrue(voice.isNotEmpty())
    }

    @Test
    fun shortPauseDoesNotSplit() {
        val out = mutableListOf<SpeechSegment>()
        run({ _, _ -> false }, mutableListOf(), out, "SS" + "_".repeat(5) + "SS" + "_".repeat(14))
        assertEquals(1, out.size)
    }

    @Test
    fun segmentInsideAgentWindowIsDropped() {
        val out = mutableListOf<SpeechSegment>()
        val voice = mutableListOf<Long>()
        run({ _, _ -> true }, voice, out, "S".repeat(5) + "_".repeat(13))
        assertTrue(out.isEmpty())
        assertTrue(voice.isEmpty())
    }

    @Test
    fun tickReportsNoAudioForSilencedCapture() {
        val seg = SpeechSegmenter(EnergyVad(), { _, _ -> false }, 0L, onSegment = {})
        seg.onFrame(PcmFrame(loud, 100))
        seg.onFrame(PcmFrame(ShortArray(512), 5000))
        val tick = seg.tick(5200)
        assertEquals(5100L, tick.noAudioForMs)
    }

    @Test
    fun tickReportsNoNoAudioWhenNothingWasEverHeard() {
        val seg = SpeechSegmenter(EnergyVad(), { _, _ -> false }, 0L, onSegment = {})
        seg.onFrame(PcmFrame(ShortArray(512), 100))
        val tick = seg.tick(60_000)
        assertEquals(0L, tick.noAudioForMs)
        assertTrue(!seg.heardAudio)
        assertEquals(60_000L, tick.silentForMs)
    }
}
