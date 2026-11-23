package com.callanswer.app.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.util.Log
import com.callanswer.app.CallAnswerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Screening_Observer (Requirements 33 and 34). Held to read the incoming number for the Ringing_Alert and, while the
 * User has turned on "silence calls from numbers not in my contacts", to mute the ringer for a non-contact that is not a
 * Repeat_Caller. It never rejects a call, never answers one, and never skips the call log or the notification.
 * Without the setting, or when the contacts cannot be read, it responds with the default [CallResponse].
 */
class RepeatCallerScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        val incoming = callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val number = if (incoming && callDetails.handlePresentation == TelecomManager.PRESENTATION_ALLOWED) {
            callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val app = applicationContext as? CallAnswerApp
        val silencer = app?.nonContactSilencer
        if (app == null || silencer == null || !incoming || !silencer.isEnabled()) {
            // Respond first and unconditionally, so nothing below can delay or change the call.
            respondToCall(callDetails, CallResponse.Builder().build())
        } else {
            app.appScope.launch {
                // The contact lookup is bounded; on a timeout or any failure the ringer stays on.
                val silence = runCatching { withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { silencer.shouldSilence(number) } }
                    .onFailure { Log.w("CallAnswer", "silence check failed: ${it.javaClass.simpleName}") }
                    .getOrNull() ?: false
                respondToCall(callDetails, CallResponse.Builder().setSilenceCall(silence).build())
            }
        }
        if (!incoming || app == null) return
        val observer = app.screeningObserver
        val scope: CoroutineScope = app.appScope
        scope.launch {
            runCatching { observer.onIncomingNumber(number) }
                .onFailure { Log.w("CallAnswer", "repeat caller check failed: ${it.javaClass.simpleName}") }
        }
    }

    private companion object {
        const val LOOKUP_TIMEOUT_MS = 3_000L
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

    /** True when the number is a Repeat_Caller that would get the Ringing_Alert; such a call is never silenced (Requirement 34). */
    suspend fun isRepeatCaller(rawNumber: String?): Boolean = enabled() && matcher.match(rawNumber) != null
}
