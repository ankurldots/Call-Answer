package com.callanswer.app.data

import com.callanswer.app.api.CompanionApi
import com.callanswer.core.model.CallLogEntry

/** Room cache of the call log; the server list is the source of truth and replaces the cache as a whole. */
class CallLogRepository(private val api: CompanionApi, private val dao: CallLogDao) {
    /** Cached entries, newest first. Works offline. */
    suspend fun cached(): List<CallLogEntry> = dao.list()

    /**
     * Reads every page from the server, then replaces the cache in one transaction. A failure (for example no connection)
     * throws [com.callanswer.app.api.ApiException] before the cache is touched.
     */
    suspend fun refresh(): List<CallLogEntry> {
        val all = ArrayList<CallLogEntry>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = api.listCalls(cursor)
            all += page.calls
            cursor = page.nextCursor
        } while (cursor != null && ++pages < MAX_PAGES)
        dao.replaceAll(all)
        return dao.list()
    }

    /** Deletes on the server first; the cached row goes only after the server accepted it (or never had the call). */
    suspend fun delete(id: String) {
        try { api.deleteCall(id) } catch (e: com.callanswer.app.api.ApiException.NotFound) { /* already gone */ }
        dao.deleteById(id)
    }

    private companion object { const val MAX_PAGES = 40 }
}
