package com.callanswer.app.screening

import com.callanswer.app.api.ApiException
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.api.RecentCaller

/**
 * Fills the [RecentCallCache] from `GET /recent-callers?minutes=60` (Requirement 33.9). It runs at app start and when the
 * call log opens. A failure (offline, signed out) leaves the cache as it is.
 */
class RecentCallSync(
    private val fetch: suspend (minutes: Int) -> List<RecentCaller>,
    private val cache: RecentCallCache,
) {
    constructor(api: CompanionApi, cache: RecentCallCache) : this({ api.recentCallers(it) }, cache)

    /** Returns true when the cache was replaced from the server. */
    suspend fun sync(): Boolean {
        val callers = try { fetch(WINDOW_MINUTES) } catch (e: ApiException) { return false }
        cache.replaceAll(callers.map { RecentCallEntry(e164 = it.number, endMs = it.endMs, intent = it.intent) })
        return true
    }

    private companion object { const val WINDOW_MINUTES = 60 }
}
