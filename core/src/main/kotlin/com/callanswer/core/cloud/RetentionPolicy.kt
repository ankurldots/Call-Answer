package com.callanswer.core.cloud

/** Retention rules. `retentionDays` is 1 to 365 (default 30). */
object RetentionPolicy {
    const val MIN_DAYS = 1
    const val MAX_DAYS = 365
    private const val DAY_MS = 86_400_000L

    fun clampDays(days: Int): Int = days.coerceIn(MIN_DAYS, MAX_DAYS)

    /** True when start time plus the retention period is not after [nowEpochMs]. */
    fun isExpired(createdAtEpochMs: Long, nowEpochMs: Long, retentionDays: Int): Boolean =
        createdAtEpochMs + clampDays(retentionDays) * DAY_MS <= nowEpochMs

    /** The latest start time (inclusive) of items that are expired at [nowEpochMs]. */
    fun cutoffMs(nowEpochMs: Long, retentionDays: Int): Long = nowEpochMs - clampDays(retentionDays) * DAY_MS

    /** Pure selection of the items whose start time plus retention is not after [nowEpochMs]. */
    fun <T> select(items: Iterable<T>, nowEpochMs: Long, retentionDays: Int, startMs: (T) -> Long): List<T> =
        items.filter { isExpired(startMs(it), nowEpochMs, retentionDays) }
}
