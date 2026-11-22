package com.callanswer.app.service

import android.content.Context
import android.media.AudioManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.delay

/** One reading of the call: telephony state (null when unreadable) and the audio mode. */
data class CallSample(val state: Int?, val mode: Int) {
    /** OFFHOOK, or the in-call audio mode. Before the answer the platform hands the app silence. */
    val active: Boolean get() = state == TelephonyManager.CALL_STATE_OFFHOOK || mode == AudioManager.MODE_IN_CALL
    val idle: Boolean get() = state == TelephonyManager.CALL_STATE_IDLE

    companion object {
        @android.annotation.SuppressLint("MissingPermission")
        fun read(context: Context): CallSample {
            val tm = context.getSystemService(TelephonyManager::class.java)
            val state = runCatching { tm?.callState }.getOrNull()
            val mode = context.getSystemService(AudioManager::class.java)?.mode ?: -1
            return CallSample(state, mode)
        }
    }
}

sealed interface CallGateResult {
    data class Active(val afterMs: Long, val sample: CallSample) : CallGateResult
    /** The call went IDLE after having rung: the Caller hung up or the answer failed. */
    data class Idle(val afterMs: Long) : CallGateResult
    data object TimedOut : CallGateResult
    data object Cancelled : CallGateResult
}

/**
 * Waits until the call is active (shared by the greeting gate and the capture gate). IDLE only counts as
 * "ended" after a non-IDLE state was seen, as in [CallEndWatcher]; an unreadable state just keeps waiting.
 */
suspend fun awaitCallActive(
    timeoutMs: Long,
    pollMs: Long = 100,
    clockMs: () -> Long,
    isCancelled: () -> Boolean = { false },
    sample: () -> CallSample,
): CallGateResult {
    val start = clockMs()
    var seenRinging = false
    while (true) {
        val s = sample()
        if (s.active) return CallGateResult.Active(clockMs() - start, s)
        if (s.state != null && !s.idle) seenRinging = true
        if (s.idle && seenRinging) return CallGateResult.Idle(clockMs() - start)
        if (isCancelled()) return CallGateResult.Cancelled
        if (clockMs() - start >= timeoutMs) return CallGateResult.TimedOut
        delay(pollMs)
    }
}
