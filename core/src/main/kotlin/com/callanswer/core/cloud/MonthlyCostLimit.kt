package com.callanswer.core.cloud

/** Where a month total stands against the User's monthly limit. */
data class LimitStatus(val reached80: Boolean, val reached100: Boolean)

/** Pure threshold arithmetic for the monthly cost limit (Requirement 22.4, 22.5). No billing is involved. */
object MonthlyCostLimit {
    /** `null` or a non-positive limit means no limit. [totalPaise] is compared with the limit in INR (100 paise each). */
    fun status(totalPaise: Long, limitInr: Int?): LimitStatus {
        if (limitInr == null || limitInr <= 0) return LimitStatus(reached80 = false, reached100 = false)
        val limitPaise = limitInr.toLong() * 100
        return LimitStatus(
            reached80 = totalPaise * 5 >= limitPaise * 4,
            reached100 = totalPaise >= limitPaise,
        )
    }

    /** True when new calls of the User take the Fallback_Route because the month total reached the limit. */
    fun limitReached(totalPaise: Long, limitInr: Int?): Boolean = status(totalPaise, limitInr).reached100
}
