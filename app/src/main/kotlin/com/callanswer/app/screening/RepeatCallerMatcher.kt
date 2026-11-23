package com.callanswer.app.screening

import com.callanswer.core.cloud.RecentCall
import com.callanswer.core.cloud.RingThroughPolicy
import com.callanswer.core.tenant.PhoneNumbers

/** A Repeat_Caller found for an incoming number. */
data class RepeatMatch(val e164: String, val record: RecentCall, val minutesAgo: Int)

/** Decides locally whether an incoming number is a Repeat_Caller, with the same rule as the server. */
class RepeatCallerMatcher(
    private val cache: RecentCallCache,
    private val windowMinutes: suspend () -> Int,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** Null for a hidden or unparsable [rawNumber] and for any number that is not a repeat. */
    suspend fun match(rawNumber: String?): RepeatMatch? {
        val e164 = rawNumber?.let(PhoneNumbers::normalize) ?: return null
        val record = cache.get(e164.value)
        val now = nowMs()
        val window = windowMinutes().coerceIn(RingThroughPolicy.MIN_WINDOW_MINUTES, RingThroughPolicy.MAX_WINDOW_MINUTES)
        if (!RingThroughPolicy.isRepeat(record, e164, now, window)) return null
        return RepeatMatch(e164.value, record!!, ((now - record.endMs) / 60_000L).toInt())
    }
}

/** Receives a Repeat_Caller match while the phone rings. [RingingAlert] implements it. */
fun interface RepeatCallerAlerter {
    fun onRepeatCaller(match: RepeatMatch)
}
