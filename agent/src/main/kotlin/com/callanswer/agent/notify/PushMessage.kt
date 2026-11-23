package com.callanswer.agent.notify

/**
 * The FCM data message contract between the Agent_Server and the Companion_App (design.md, "Push notifications").
 *
 * Every message is an FCM HTTP v1 *data* message (no `notification` block) with `android.priority = HIGH`, so the app
 * builds the notification and its sound. FCM requires every data value to be a string, so numbers are decimal strings.
 * The key `type` is always present and holds one of the [PushType.wire] values. Further keys per type:
 *
 * | type                    | keys besides `type`                                                              |
 * | ----------------------- | -------------------------------------------------------------------------------- |
 * | `CALL_STARTED`          | `callId`, `number` (E.164 or empty when hidden), `startedAtMs`                   |
 * | `TAKEOVER_ALERT`        | `callId`, `number` (E.164 or empty), `intent`, `requestedAtMs`                   |
 * | `CALL_ENDED`            | `callId`, `outcome`, `endMs`                                                     |
 * | `UNKNOWN_CALLER_SUMMARY`| `callId`, `number` (E.164 or empty), `summary`                                   |
 * | `SILENT_CALLER`         | `callId`, `number` (E.164 or empty)                                              |
 * | `send_map_sms`          | `callId`, `number` (E.164 of the Caller), `text` (the Map_SMS text, ready to send)|
 * | `MAP_SMS_FAILED`        | `callId`, `reason`                                                               |
 * | `MAP_LOCATION_MISSING`  | `callId`                                                                         |
 * | `PII_EVENT`             | `callId`                                                                         |
 * | `NOT_LOGGED`            | `callId`                                                                         |
 * | `COST_80`, `COST_100`   | `yearMonth` (`YYYY-MM`), `totalPaise`                                            |
 * | `recent_caller`         | `number` (E.164), `endMs`, `intent` (IntentCategory name), `callId` (omitted if none) |
 *
 * `send_map_sms` is data-only and silent: the app sends the text by SMS from the User's own SIM and answers with
 * `POST /calls/{id}/map-sms-result`. `recent_caller` is data-only and shows no notification: the app stores it in
 * its `RecentCallCache`. `TAKEOVER_ALERT` must be answered with `POST /calls/{id}/alert-ack` within 5 seconds of
 * arrival. Values that are absent are omitted, never sent as the string "null".
 */
enum class PushType(val wire: String) {
    CALL_STARTED("CALL_STARTED"),
    TAKEOVER_ALERT("TAKEOVER_ALERT"),
    CALL_ENDED("CALL_ENDED"),
    UNKNOWN_CALLER_SUMMARY("UNKNOWN_CALLER_SUMMARY"),
    SILENT_CALLER("SILENT_CALLER"),
    SEND_MAP_SMS("send_map_sms"),
    MAP_SMS_FAILED("MAP_SMS_FAILED"),
    MAP_LOCATION_MISSING("MAP_LOCATION_MISSING"),
    PII_EVENT("PII_EVENT"),
    NOT_LOGGED("NOT_LOGGED"),
    COST_80("COST_80"),
    COST_100("COST_100"),
    RECENT_CALLER("recent_caller"),
}

/** One data message. [data] never contains `type`; [toData] adds it. */
data class PushMessage(val type: PushType, val data: Map<String, String> = emptyMap()) {
    init {
        require("type" !in data) { "type is set by the contract" }
    }

    fun toData(): Map<String, String> = linkedMapOf("type" to type.wire) + data
}

/** Builders that keep the key names of the contract in one place. */
object PushMessages {
    fun callStarted(callId: String, number: String?, startedAtMs: Long) =
        msg(PushType.CALL_STARTED, "callId" to callId, "number" to number.orEmpty(), "startedAtMs" to startedAtMs.toString())

    fun takeoverAlert(callId: String, number: String?, intent: String, requestedAtMs: Long) = msg(
        PushType.TAKEOVER_ALERT, "callId" to callId, "number" to number.orEmpty(), "intent" to intent,
        "requestedAtMs" to requestedAtMs.toString(),
    )

    fun callEnded(callId: String, outcome: String, endMs: Long) =
        msg(PushType.CALL_ENDED, "callId" to callId, "outcome" to outcome, "endMs" to endMs.toString())

    fun unknownCallerSummary(callId: String, number: String?, summary: String) =
        msg(PushType.UNKNOWN_CALLER_SUMMARY, "callId" to callId, "number" to number.orEmpty(), "summary" to summary)

    fun silentCaller(callId: String, number: String?) =
        msg(PushType.SILENT_CALLER, "callId" to callId, "number" to number.orEmpty())

    fun sendMapSms(callId: String, number: String, text: String) =
        msg(PushType.SEND_MAP_SMS, "callId" to callId, "number" to number, "text" to text)

    fun mapSmsFailed(callId: String, reason: String) = msg(PushType.MAP_SMS_FAILED, "callId" to callId, "reason" to reason)
    fun mapLocationMissing(callId: String) = msg(PushType.MAP_LOCATION_MISSING, "callId" to callId)
    fun piiEvent(callId: String) = msg(PushType.PII_EVENT, "callId" to callId)
    fun notLogged(callId: String) = msg(PushType.NOT_LOGGED, "callId" to callId)

    fun cost80(yearMonth: String, totalPaise: Long) =
        msg(PushType.COST_80, "yearMonth" to yearMonth, "totalPaise" to totalPaise.toString())

    fun cost100(yearMonth: String, totalPaise: Long) =
        msg(PushType.COST_100, "yearMonth" to yearMonth, "totalPaise" to totalPaise.toString())

    fun recentCaller(number: String, endMs: Long, intent: String, callId: String?): PushMessage {
        val pairs = mutableListOf("number" to number, "endMs" to endMs.toString(), "intent" to intent)
        if (callId != null) pairs += "callId" to callId
        return msg(PushType.RECENT_CALLER, *pairs.toTypedArray())
    }

    private fun msg(type: PushType, vararg pairs: Pair<String, String>) = PushMessage(type, linkedMapOf(*pairs))
}
