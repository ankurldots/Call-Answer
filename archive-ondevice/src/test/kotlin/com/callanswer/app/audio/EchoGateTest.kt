package com.callanswer.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGateTest {
    @Test fun framesInsideWindowAreMarked() {
        val g = EchoGate()
        g.markPlayback(1_000, 2_000) // window 950..2600
        assertTrue(g.isMarked(PcmFrame(ShortArray(0), 950)))
        assertTrue(g.isMarked(PcmFrame(ShortArray(0), 2_600)))
        assertFalse(g.isMarked(PcmFrame(ShortArray(0), 949)))
        assertFalse(g.isMarked(PcmFrame(ShortArray(0), 2_601)))
    }

    @Test fun fullCoverageNeedsWholeSegmentInside() {
        val g = EchoGate()
        g.markPlayback(1_000, 2_000)
        assertTrue(g.isFullyCovered(1_000, 2_500))
        assertFalse(g.isFullyCovered(2_000, 3_000))
    }

    @Test fun adjacentWindowsCoverTogether() {
        val g = EchoGate()
        g.markPlayback(0, 1_000)      // -50..1600
        g.markAlertTone(1_500, 2_000) // 1450..2600
        assertTrue(g.isFullyCovered(0, 2_500))
    }
}
