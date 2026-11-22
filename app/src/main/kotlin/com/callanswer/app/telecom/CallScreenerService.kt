package com.callanswer.app.telecom

import android.content.Context
import android.telecom.Call
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
            CallForegroundService.startOrFallback(ctx, number, frozen)
            TelecomCallAnswerer(ctx, number).answer()
        }
    }

    override fun onDestroy() {
        // Do not cancel the scope: the answer retries may outlive the screening binding.
        super.onDestroy()
    }

    private companion object {
        const val SETTINGS_TIMEOUT_MS = 1_000L
    }
}
