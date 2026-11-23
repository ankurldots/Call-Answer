package com.callanswer.app.push

import com.callanswer.app.CallAnswerApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Receives the FCM data messages of the Agent_Server and the new tokens of this device. */
class CallAnswerMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as CallAnswerApp
        val parsed = PushMessageParser.parse(message.data)
        when (parsed) {
            // Long or unbounded work (the SMS send waits for the radio) continues after this callback returns.
            is PushMessage.SendMapSms, is PushMessage.RecentCaller -> app.appScope.launch { app.pushHandler.handle(parsed) }
            // The alert must be on screen and acknowledged (at most 5 s) before the callback ends.
            else -> runBlocking(Dispatchers.IO) { app.pushHandler.handle(parsed) }
        }
    }

    override fun onNewToken(token: String) {
        val app = application as CallAnswerApp
        app.appScope.launch { app.pushRegistration.register(token) }
    }
}
