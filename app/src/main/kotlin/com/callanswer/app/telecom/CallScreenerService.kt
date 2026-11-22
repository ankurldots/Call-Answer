package com.callanswer.app.telecom

import android.content.Context
import android.telecom.Call
import android.util.Log
import com.callanswer.app.CallLog
import com.callanswer.app.service.CallOverlay
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.service.CallForegroundService
import com.callanswer.app.service.NotificationCenter
import com.callanswer.core.model.FrozenSettings
import com.callanswer.core.phone.ContactIndex
import com.callanswer.core.phone.Decision
import com.callanswer.core.phone.ScreeningDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Process-wide [ContentContactIndex], created lazily and reloaded while it is unreadable. */
object ContactIndexHolder {
    @Volatile private var index: ContentContactIndex? = null

    fun get(context: Context): ContentContactIndex {
        val i = index ?: synchronized(this) {
            index ?: ContentContactIndex(context.applicationContext).also { index = it; it.load() }
        }
        if (!i.readable) i.load()
        return i
    }
}

/**
 * Screens every incoming call. Known contacts keep ringing; unknown and hidden numbers are allowed through,
 * the foreground service is started and the call is answered. When contacts cannot be read the call keeps
 * ringing and a notification is posted immediately (well inside the 5 s limit).
 */
class CallScreenerService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onScreenCall(callDetails: Call.Details) {
        // Always allow: the service never rejects or silences a call.
        respondToCall(callDetails, CallResponse.Builder().build())
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return

        Log.i(TAG, "call screened (incoming)")
        val number = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val hidden = number == null || callDetails.handlePresentation != TelecomManager.PRESENTATION_ALLOWED
        val store = SettingsStore.create(applicationContext)
        val settings = runBlocking { withTimeoutOrNull(SETTINGS_TIMEOUT_MS) { runCatching { store.current() }.getOrNull() } }
        val index: ContactIndex = ContactIndexHolder.get(applicationContext)

        val decision = ScreeningDecision.decide(
            screeningEnabled = settings?.screeningEnabled ?: true,
            contactsReadable = index.readable,
            numberHidden = hidden,
            knownContact = !hidden && index.isKnown(number!!),
        )
        when (decision) {
            Decision.LEAVE_RINGING -> Unit
            Decision.LEAVE_RINGING_NOTIFY_NO_CONTACT_ACCESS -> NotificationCenter(applicationContext).contactsAccessRequired()
            Decision.ANSWER -> answer(if (hidden) null else number, store)
        }
    }

    private fun answer(number: String?, store: SettingsStore) {
        val ctx = applicationContext
        scope.launch {
            val frozen: FrozenSettings? = runCatching { store.snapshot() }.getOrNull()
            // Visible overlay first: with SYSTEM_ALERT_WINDOW it is what allows a background FGS start (Android 15+).
            val overlay = CallOverlay.show(ctx)
            val outcome = StartThenAnswer(
                start = { CallForegroundService.start(ctx, number, frozen) },
                awaitRunning = {
                    CallForegroundService.awaitRunning(START_CONFIRM_TIMEOUT_MS).also {
                        Log.i(TAG, "service foreground confirmed=$it")
                    }
                },
                answer = { TelecomCallAnswerer(ctx, number).answer() },
                onNotStarted = {
                    // Leave the call ringing; the User taps the alert and the activity starts the service.
                    if (CallForegroundService.running.value) {
                        CallLog.i("not started reported but service is running; fallback alert not posted")
                    } else {
                        CallOverlay.hide(ctx)
                        CallForegroundService.postFallback(ctx, number)
                    }
                },
            ).run()
            CallLog.i("screened call outcome=$outcome overlay=$overlay")
            if (outcome == StartThenAnswer.Outcome.ANSWERED) {
                CallForegroundService.cancelFallback(ctx)
                launchLiveCall(ctx)
            }
        }
    }

    /**
     * A visible activity must exist so the `microphone` foreground type can be added (while-in-use permission).
     * With the overlay window visible the background activity start is allowed; the FGS notification's
     * full-screen intent is the second path.
     */
    private fun launchLiveCall(ctx: Context) {
        val overlay = CallOverlay.isShowing
        val started = runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, com.callanswer.app.service.LiveCallActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.onFailure { CallLog.w("LiveCallActivity auto-launch threw", it) }.isSuccess
        scope.launch {
            kotlinx.coroutines.delay(1_500L)
            CallLog.i("LiveCallActivity auto-launch: startActivity ok=$started overlayVisible=$overlay " +
                "shown=${com.callanswer.app.service.LiveCallActivity.visible}")
        }
    }

    override fun onDestroy() {
        // Do not cancel the scope: the answer retries may outlive the screening binding.
        super.onDestroy()
    }

    private companion object {
        const val TAG = "CallAnswer"
        const val START_CONFIRM_TIMEOUT_MS = 3_000L // covers a cold process start plus the denied-type fallback
        const val SETTINGS_TIMEOUT_MS = 1_000L
    }
}
