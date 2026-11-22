package com.callanswer.app.audio

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class CaptureGuardsTest {
    @Test fun modeAlreadyInCommunicationIsNotRejected() {
        assertFalse(AudioModeGuard.rejects(before = 3, after = 3))
    }
    @Test fun modeChangedByTrackIsRejected() {
        assertTrue(AudioModeGuard.rejects(before = 2, after = 3))
    }
    @Test fun unknownModeNeverRejects() {
        assertFalse(AudioModeGuard.rejects(null, 3))
        assertFalse(AudioModeGuard.rejects(3, null))
    }
    @Test fun nearSilenceIsNotUsable() {
        assertFalse(AudioActivity.isUsable(0.3, 2))
        assertFalse(AudioActivity.isUsable(29.9, 299))
    }
    @Test fun rmsOrPeakThresholdMakesItUsable() {
        assertTrue(AudioActivity.isUsable(30.0, 0))
        assertTrue(AudioActivity.isUsable(1.0, 300))
        assertTrue(AudioActivity.isUsable(1512.0, 9000))
    }
}
