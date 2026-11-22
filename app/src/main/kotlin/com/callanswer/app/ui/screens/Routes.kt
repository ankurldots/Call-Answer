package com.callanswer.app.ui.screens

/**
 * Every navigation destination of the app, in one place so that screen tasks add their route here once and only
 * register the composable in [AppNavGraph].
 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"

    // Settings
    const val SETTINGS = "settings"
    const val SETTINGS_GENERAL = "settings/general"
    const val SETTINGS_GATES = "settings/gates"
    const val SETTINGS_CALL = "settings/call"
    const val SETTINGS_SPAM = "settings/spam"
    const val SETTINGS_STEPS = "settings/steps"
    const val SETTINGS_ACCOUNT = "settings/account"
    /** Cloud Agent settings of Requirement 20 (User_Mobile, voice, retention, cost rates, limit). */
    const val SETTINGS_AGENT = "settings/agent"
    /** Per-field choice for settings changed on both sides. */
    const val SETTINGS_CONFLICTS = "settings/conflicts"

    // Privacy_Consent
    const val CONSENT = "consent"

    // Call forwarding
    const val FORWARDING_SETUP = "forwarding/setup"
    const val FORWARDING_TURN_OFF = "forwarding/turnoff"
    const val FORWARDING_TEST = "forwarding/test"

    // Call log, call detail, cost
    const val CALL_LOG = "calllog"
    const val CALL_DETAIL = "calllog/{entryId}"
    const val COST = "cost"

    // Live call (opened from the Takeover_Alert and from the call list)
    const val LIVE_CALL = "live/{callId}"

    fun callDetail(id: String) = "calllog/" + android.net.Uri.encode(id)
    fun liveCall(id: String) = "live/" + android.net.Uri.encode(id)
}
