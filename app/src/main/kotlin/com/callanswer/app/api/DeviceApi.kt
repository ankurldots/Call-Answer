package com.callanswer.app.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `PUT /devices/{deviceId}` with `{"fcmToken": ...}`: stores or replaces the FCM token of this device (up to 5 per User). */
suspend fun CompanionApi.registerDevice(deviceId: String, fcmToken: String) {
    send("PUT", "/devices/${pathSegment(deviceId)}", buildJsonObject { put("fcmToken", fcmToken) }.toString())
}

/** `POST /calls/{id}/alert-ack`: tells the server that this device received the Takeover_Alert. */
suspend fun CompanionApi.alertAck(callId: String) { send("POST", "/calls/${pathSegment(callId)}/alert-ack") }

private fun pathSegment(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
