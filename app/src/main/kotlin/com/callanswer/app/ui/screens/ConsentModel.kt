package com.callanswer.app.ui.screens

import com.callanswer.app.R
import com.callanswer.app.api.ApiException

/** Version of the consent text shown on the Privacy_Consent screen. Change it whenever the text changes. */
const val CONSENT_TEXT_VERSION = "2025-01-tokyo-v1"

/** Retention_Period default shown in the consent text (Requirement 17 criterion 6). */
const val CONSENT_DEFAULT_RETENTION_DAYS = 30

/** What the Privacy_Consent screen knows about the User's consent. */
sealed interface ConsentStatus {
    data object Loading : ConsentStatus
    data object NotAccepted : ConsentStatus
    data object Accepted : ConsentStatus
    /** The status could not be read; nothing is enabled. */
    data class Failed(val messageRes: Int) : ConsentStatus
}

/** Forwarding codes and the Agent switch are available only after the consent is accepted (Requirement 17 criterion 2). */
fun ConsentStatus.unlocksAgentAndCodes(): Boolean = this == ConsentStatus.Accepted

/** Message for a failed consent request. */
fun consentErrorText(e: ApiException): Int = when (e) {
    is ApiException.Network -> R.string.consent_err_network
    is ApiException.Unauthorized -> R.string.consent_err_unauthorized
    else -> R.string.consent_err_other
}
