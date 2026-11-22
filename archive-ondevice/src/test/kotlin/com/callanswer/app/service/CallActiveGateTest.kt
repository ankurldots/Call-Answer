package com.callanswer.app.service

import android.media.AudioManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallActiveGateTest {
    private val ring = CallSample(TelephonyManager.CALL_STATE_RINGING, AudioManager.MODE_RINGTONE)
    private val off = CallSample(TelephonyManager.CALL_STATE_OFFHOOK, AudioManager.MODE_IN_CALL)
    private val idle = CallSample(TelephonyManager.CALL_STATE_IDLE, AudioManager.MODE_NORMAL)

    private fun run(timeout: Long, seq: List<CallSample>): CallGateResult = runBlocking {
        var i = 0
        var t = 0L
        awaitCallActive(timeout, pollMs = 1, clockMs = { t++ }) { seq[minOf(i++, seq.lastIndex)] }
    }

    @Test fun waitsWhileRingingThenActive() =
        assertTrue(run(1000, listOf(ring, ring, off)) is CallGateResult.Active)

    @Test fun idleAfterRingingEndsWithoutGreeting() =
        assertTrue(run(1000, listOf(ring, idle)) is CallGateResult.Idle)

    @Test fun idleBeforeAnyRingKeepsWaitingUntilTimeout() =
        assertEquals(CallGateResult.TimedOut, run(10, listOf(idle)))

    @Test fun neverActiveTimesOut() =
        assertEquals(CallGateResult.TimedOut, run(10, listOf(ring)))
}
