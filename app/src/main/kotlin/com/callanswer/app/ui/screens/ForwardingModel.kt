package com.callanswer.app.ui.screens

import com.callanswer.app.api.Profile

/** Total wait of the test call (Requirement 2). */
const val TEST_CALL_WAIT_SEC = 600

/** What the forwarding screens may show for the signed-in User. */
sealed interface ForwardingAccess {
    data object Loading : ForwardingAccess
    /** Consent is not accepted, so the codes stay hidden (Requirement 17 criterion 2). */
    data object Locked : ForwardingAccess
    data object NoNumber : ForwardingAccess
    data class Ready(val virtualNumber: String) : ForwardingAccess
    data class Failed(val messageRes: Int) : ForwardingAccess
}

/** Consent first, then a Virtual_Number must be assigned. */
fun forwardingAccess(profile: Profile): ForwardingAccess {
    val consent = if (profile.consentAccepted) ConsentStatus.Accepted else ConsentStatus.NotAccepted
    if (!consent.unlocksAgentAndCodes()) return ForwardingAccess.Locked
    val number = profile.virtualNumber
    return if (number.isNullOrBlank()) ForwardingAccess.NoNumber else ForwardingAccess.Ready(number)
}

/** `m:ss` for the countdown. */
fun formatCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
