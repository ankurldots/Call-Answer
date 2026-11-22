package com.callanswer.app.ui.screens

import com.callanswer.app.api.LiveEvent
import com.callanswer.app.ui.components.BubbleSpeaker
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Bridge_Call status of a take over. */
enum class BridgeStatus { NONE, CONNECTING, CONNECTED, FAILED }

data class LiveLine(val speaker: BubbleSpeaker, val text: String)

/** State of the live call screen, built from `/live` events by [reduce]. */
data class LiveCallState(
    val callId: String,
    val callerNumber: String? = null,
    val intent: String? = null,
    val language: String? = null,
    val startedAtMs: Long? = null,
    val endedAtMs: Long? = null,
    val ended: Boolean = false,
    val bridge: BridgeStatus = BridgeStatus.NONE,
    val transcript: List<LiveLine> = emptyList(),
) {
    /** Take over is possible while the call is live and no bridge is pending or connected. */
    val canTakeOver: Boolean get() = !ended && bridge != BridgeStatus.CONNECTING && bridge != BridgeStatus.CONNECTED

    /** The server ends no call that is in Take_Over (Requirement 19 criterion 6). */
    val canEnd: Boolean get() = !ended && bridge != BridgeStatus.CONNECTED

    val canQuickReply: Boolean get() = !ended && bridge != BridgeStatus.CONNECTING && bridge != BridgeStatus.CONNECTED

    fun elapsedSec(nowMs: Long): Long {
        val start = startedAtMs ?: return 0
        return ((endedAtMs ?: nowMs) - start).coerceAtLeast(0) / 1000
    }

    /** Text for a quick reply in the Caller's language: Hindi text when the language is Hindi and the text exists. */
    fun quickReplyText(en: String, hi: String): String = if (language?.startsWith("hi", ignoreCase = true) == true && hi.isNotBlank()) hi else en

    /**
     * Applies one event. Parsing is tolerant: unknown types, missing fields, and events of another call are ignored.
     * [nowMs] stamps the start or end when the event carries no time.
     */
    fun reduce(event: LiveEvent, nowMs: Long): LiveCallState {
        val d = event.data
        val id = d.str("callId", "call_id", "id")
        if (id != null && id != callId) return this
        return when (event.type) {
            "call_started" -> copy(
                callerNumber = d.str("callerNumber", "caller", "from", "caller_number") ?: callerNumber,
                intent = d.str("intent") ?: intent,
                language = d.str("language") ?: language,
                startedAtMs = d.time("startedAtEpochMs", "startedAt", "started_at") ?: startedAtMs ?: nowMs,
            )
            "transcript" -> {
                val text = d.str("text", "utterance")?.trim().orEmpty()
                if (text.isEmpty()) this else copy(transcript = transcript + LiveLine(speakerOf(d.str("speaker", "role")), text))
            }
            "intent" -> copy(intent = d.str("intent", "value", "category") ?: intent)
            "language" -> copy(language = d.str("language", "value", "lang") ?: language)
            "state" -> copy(
                callerNumber = d.str("callerNumber", "caller") ?: callerNumber,
                intent = d.str("intent") ?: intent,
                language = d.str("language") ?: language,
                startedAtMs = d.time("startedAtEpochMs", "startedAt") ?: startedAtMs,
            )
            "takeover_status" -> copy(bridge = bridgeOf(d.str("status", "state", "bridge")) ?: bridge)
            "call_ended" -> copy(ended = true, endedAtMs = d.time("endedAtEpochMs", "endedAt") ?: endedAtMs ?: nowMs)
            else -> this
        }
    }

    companion object {
        fun speakerOf(s: String?): BubbleSpeaker = when (s?.lowercase()) {
            "agent", "assistant", "model" -> BubbleSpeaker.AGENT
            "user" -> BubbleSpeaker.USER
            "live" -> BubbleSpeaker.LIVE
            else -> BubbleSpeaker.CALLER
        }

        fun bridgeOf(s: String?): BridgeStatus? = when (s?.lowercase()) {
            "connecting", "requested", "pending" -> BridgeStatus.CONNECTING
            "connected", "bridged" -> BridgeStatus.CONNECTED
            "failed", "failure", "error" -> BridgeStatus.FAILED
            "none", "idle" -> BridgeStatus.NONE
            else -> null
        }
    }
}

private fun JsonObject.str(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }

private fun JsonObject.time(vararg keys: String): Long? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonElement as? JsonPrimitive)?.longOrNull }

/** Elapsed time as `m:ss`, or `h:mm:ss` from one hour. */
fun formatElapsed(totalSec: Long): String {
    val s = totalSec.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
