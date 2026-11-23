package com.callanswer.core.cloud

import com.callanswer.core.model.IntentCategory
import com.callanswer.core.tenant.E164

/** The latest non-SOLICITATION call of one Caller number for one User. */
data class RecentCall(
    val endMs: Long,
    val intent: IntentCategory,
    val callId: String? = null,
)

/** Pure Repeat_Caller rule shared by the server and the app (Requirements 32.2 to 32.4, 33). */
object RingThroughPolicy {
    const val MIN_WINDOW_MINUTES = 1
    const val MAX_WINDOW_MINUTES = 60
    const val DEFAULT_WINDOW_MINUTES = 15
    const val MIN_MAX_SECONDS = 30
    const val MAX_MAX_SECONDS = 600
    const val DEFAULT_MAX_SECONDS = 180

    /**
     * True when the number is not hidden, a record exists, `now - record.endMs` is from 0 to the window
     * (both ends inclusive), and the record's intent is not SOLICITATION. UNKNOWN counts as a repeat.
     */
    fun isRepeat(record: RecentCall?, callerE164: E164?, now: Long, windowMinutes: Int): Boolean {
        if (callerE164 == null || record == null) return false
        if (record.intent == IntentCategory.SOLICITATION) return false
        val elapsed = now - record.endMs
        return elapsed >= 0 && elapsed <= windowMinutes * 60_000L
    }
}

/** State of one ring-through call. [dialLegs] counts the Dial_Legs started so far. */
data class RingThroughState(
    val startMs: Long,
    val dialLegs: Int = 0,
    val maxSeconds: Int = RingThroughPolicy.DEFAULT_MAX_SECONDS,
)

sealed class RedialAction {
    /** Start the next Dial_Leg now with this timeout. */
    data class DialNow(val timeoutSeconds: Int) : RedialAction()
    /** Play the Ringback_Tone until [untilMs], then ask again. */
    data class Ringback(val untilMs: Long) : RedialAction()
    /** Play the not-available message and end the call. */
    data object Final : RedialAction()
}

/** Pure redial cadence: Dial_Leg `k` starts `17 * k` seconds after the start (Requirement 32.8, 32.9). */
object RedialSchedule {
    const val CADENCE_SECONDS = 17
    const val LEG_TIMEOUT_SECONDS = 15
    const val MIN_LEG_SECONDS = 5

    fun next(state: RingThroughState, now: Long): RedialAction {
        val offset = CADENCE_SECONDS.toLong() * state.dialLegs
        val timeout = minOf(LEG_TIMEOUT_SECONDS.toLong(), state.maxSeconds - offset)
        if (offset >= state.maxSeconds || timeout < MIN_LEG_SECONDS) return RedialAction.Final
        val startAt = state.startMs + offset * 1000
        return if (now >= startAt) RedialAction.DialNow(timeout.toInt()) else RedialAction.Ringback(startAt)
    }
}
