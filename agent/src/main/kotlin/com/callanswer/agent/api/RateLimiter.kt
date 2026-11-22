package com.callanswer.agent.api

import com.callanswer.agent.Clock
import com.callanswer.agent.SystemClock

/** Sliding-window limit: at most [limit] requests per [windowMillis] per identity (the token's `sub`). */
class RateLimiter(
    private val limit: Int = 60,
    private val windowMillis: Long = 60_000,
    private val clock: Clock = SystemClock,
) {
    private val hits = HashMap<String, ArrayDeque<Long>>()

    /** Records one request of [key]; returns false when it exceeds the limit. */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = clock.nowMillis()
        val q = hits.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() >= windowMillis) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(now)
        if (hits.size > 10_000) hits.entries.removeIf { it.value.isEmpty() || now - it.value.last() >= windowMillis }
        return true
    }
}
