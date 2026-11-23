package com.callanswer.app.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log
import com.callanswer.app.CallAnswerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Screening_Observer (Requirement 33). Held only to read the incoming number for the Ringing_Alert.
 * It ALWAYS lets the call through unchanged: the default [CallResponse] does not reject, silence, skip the call log,
 * or skip the notification. It never answers a call.
 */
class RepeatCallerScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        // Respond first and unconditionally, so nothing below can delay or change the call.
        respondToCall(callDetails, CallResponse.Builder().build())
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return
        val number = if (callDetails.handlePresentation == TelecomManager.PRESENTATION_ALLOWED) {
            callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val app = applicationContext as? CallAnswerApp ?: return
        val observer = app.screeningObserver
        val scope: CoroutineScope = app.appScope
        scope.launch {
            runCatching { observer.onIncomingNumber(number) }
                .onFailure { Log.w("CallAnswer", "repeat caller check failed: ${it.javaClass.simpleName}") }
        }
    }
}

/** Matches an incoming number and hands a Repeat_Caller to the alerter. Nothing else. */
class ScreeningObserver(
    private val matcher: RepeatCallerMatcher,
    private val alerter: RepeatCallerAlerter,
    /** Ring_Through setting; no alert while it is off (Requirement 33.3). */
    private val enabled: suspend () -> Boolean = { true },
) {
    suspend fun onIncomingNumber(rawNumber: String?) {
        if (!enabled()) return
        matcher.match(rawNumber)?.let(alerter::onRepeatCaller)
    }
}
