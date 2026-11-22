package com.callanswer.app.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Live call actions of the Companion_API (`POST /calls/{id}/takeover`, `/end`, `/quick-reply`). The server decides the
 * destination of a take over (Requirement 28 criterion 6), so the request has no number. A foreign call identifier
 * answers 404, which surfaces as [ApiException.NotFound].
 */
suspend fun CompanionApi.takeOver(callId: String) { send("POST", "/calls/${pathId(callId)}/takeover") }

suspend fun CompanionApi.endCall(callId: String) { send("POST", "/calls/${pathId(callId)}/end") }

suspend fun CompanionApi.sendQuickReply(callId: String, text: String) {
    send("POST", "/calls/${pathId(callId)}/quick-reply", buildJsonObject { put("text", text) }.toString())
}

private fun pathId(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
