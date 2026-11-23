package com.callanswer.app.push

import com.callanswer.app.screening.RecentCallCache
import com.callanswer.app.screening.RecentCallEntry
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.tenant.PhoneNumbers

/**
 * Stores the Recent_Call_Record of a `recent_caller` push in the [RecentCallCache] without a notification
 * (Requirement 33.8). Parsing is tolerant: `endMs` also reads as `end_ms`, `callId` as `call_id`, the intent without
 * case and with `-` or space read as `_`. A record without a usable number or `endMs` is dropped; an unknown or missing
 * intent reads as UNKNOWN; a SOLICITATION record is not stored. An older record never replaces a newer one (the cache
 * keeps the newer).
 */
class RecentCallerPush(private val cache: RecentCallCache) {
    suspend fun handle(data: Map<String, String>) {
        val entry = parse(data) ?: return
        cache.put(entry)
    }

    companion object {
        fun parse(data: Map<String, String>): RecentCallEntry? {
            val raw = (data["number"] ?: data["callerNumber"] ?: data["caller"])?.trim()?.takeIf { it.isNotEmpty() }
            val e164 = raw?.let(PhoneNumbers::normalize)?.value ?: return null
            val endMs = (data["endMs"] ?: data["end_ms"])?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val intentName = data["intent"]?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_')
            val intent = IntentCategory.values().firstOrNull { it.name == intentName } ?: IntentCategory.UNKNOWN
            if (intent == IntentCategory.SOLICITATION) return null
            val callId = (data["callId"] ?: data["call_id"])?.trim()?.takeIf { it.isNotEmpty() }
            return RecentCallEntry(e164 = e164, endMs = endMs, intent = intent, callId = callId)
        }
    }
}
