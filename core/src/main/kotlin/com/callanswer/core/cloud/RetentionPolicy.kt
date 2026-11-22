package com.callanswer.core.cloud

/** Retention rules. `retentionDays` is 1 to 365 (default 30). */
object RetentionPolicy {
    const val MIN_DAYS = 1
    const val MAX_DAYS = 365
    private const val DAY_MS = 86_400_000L

    fun clampDays(days: Int): Int = days.coerceIn(MIN_DAYS, MAX_DAYS)

    /** True when a record created at [createdAtEpochMs] is older than the retention period at [nowEpochMs]. */
    fun isExpired(createdAtEpochMs: Long, nowEpochMs: Long, retentionDays: Int): Boolean =
        nowEpochMs - createdAtEpochMs > clampDays(retentionDays) * DAY_MS
}
