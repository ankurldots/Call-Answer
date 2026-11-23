package com.callanswer.app.push

/**
 * An FCM data message of the Agent_Server (design, "Push notifications"). The `type` key names the kind. Parsing is
 * tolerant: the type is matched without case and with `-` or space read as `_`, `callId` also reads as `call_id`, and a
 * missing optional key gives null. A message without a usable type or without its required keys is [Ignored].
 */
sealed interface PushMessage {
    data class CallStarted(val callId: String, val number: String?) : PushMessage
    data class TakeoverAlert(val callId: String, val number: String?) : PushMessage
    data class CallEnded(val callId: String) : PushMessage
    /** The Map_SMS_Request: send [text] to [number] from this phone's SIM (Requirement 10.13). */
    data class SendMapSms(val callId: String, val number: String, val text: String) : PushMessage
    /** A Recent_Call_Record for the cache (task 12.26 reads [data]). */
    data class RecentCaller(val data: Map<String, String>) : PushMessage
    /** Any other type, or a message that lacks required keys. */
    data class Ignored(val type: String?) : PushMessage
}

object PushMessageParser {
    fun parse(data: Map<String, String>): PushMessage {
        val type = data["type"]?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_')
        val callId = (data["callId"] ?: data["call_id"])?.trim()?.takeIf { it.isNotEmpty() }
        val number = (data["number"] ?: data["callerNumber"] ?: data["caller"])?.trim()?.takeIf { it.isNotEmpty() }
        return when (type) {
            "CALL_STARTED" -> callId?.let { PushMessage.CallStarted(it, number) }
            "TAKEOVER_ALERT" -> callId?.let { PushMessage.TakeoverAlert(it, number) }
            "CALL_ENDED" -> callId?.let { PushMessage.CallEnded(it) }
            "SEND_MAP_SMS" -> {
                val text = (data["text"] ?: data["message"])?.takeIf { it.isNotBlank() }
                if (callId != null && number != null && text != null) PushMessage.SendMapSms(callId, number, text) else null
            }
            "RECENT_CALLER" -> PushMessage.RecentCaller(data)
            else -> null
        } ?: PushMessage.Ignored(type)
    }
}
