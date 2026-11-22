package com.callanswer.app.ui.screens

import com.callanswer.app.api.Profile
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.Settings
import com.callanswer.core.settings.SettingsValidator
import com.callanswer.core.settings.ValidationResult
import kotlinx.serialization.json.JsonObject

/** The profile is polled at most this often while the "waiting for your number" screen is open (Requirement 31 criterion 2). */
const val NUMBER_POLL_INTERVAL_MS = 60_000L

/** Guided steps after the first sign-in, in order (Requirement 31 criterion 1; the mobile entry comes before the codes, criterion 4). */
enum class OnboardingStep { SIGN_IN, CONSENT, NUMBER, MOBILE, FORWARDING, SETTINGS }

/** What the Agent still needs before it may be turned on (Requirement 31 criterion 6). */
enum class AgentPrerequisite { SIGN_IN, CONSENT, NUMBER, USER_MOBILE }

/** True when [mobile] is a valid E.164 User_Mobile that is not the assigned Virtual_Number. */
fun isValidUserMobile(mobile: String?, virtualNumber: String?): Boolean =
    userMobileError(mobile, virtualNumber) == null && !mobile.isNullOrBlank()

/** Message for an invalid User_Mobile entry, or null when it is valid. A blank entry gives a message. */
fun userMobileError(mobile: String?, virtualNumber: String?): String? {
    val text = mobile.orEmpty().trim()
    if (text.isEmpty()) return "Enter your mobile number in international format, for example +919876543210"
    val result = SettingsValidator.validateUserMobile(text) { it == virtualNumber }
    return (result as? ValidationResult.Invalid)?.errors?.first()?.message
}

/** Prerequisites that are missing, in step order. Empty when the Agent may be turned on. */
fun missingPrerequisites(signedIn: Boolean, profile: Profile?, userMobile: String?): List<AgentPrerequisite> = buildList {
    if (!signedIn) { add(AgentPrerequisite.SIGN_IN); return@buildList }
    if (profile?.consentAccepted != true) add(AgentPrerequisite.CONSENT)
    if (profile?.virtualNumber.isNullOrBlank()) add(AgentPrerequisite.NUMBER)
    if (!isValidUserMobile(userMobile, profile?.virtualNumber)) add(AgentPrerequisite.USER_MOBILE)
}

/** The Agent stays off until consent is accepted, a number is assigned, and a valid User_Mobile is saved. */
fun agentMayTurnOn(signedIn: Boolean, profile: Profile?, userMobile: String?): Boolean =
    missingPrerequisites(signedIn, profile, userMobile).isEmpty()

/**
 * The step to show. Consent, number and User_Mobile are read from the server state, so the flow resumes where it stopped.
 * [numberSeen] means the User saw the assigned number and continued; [forwardingDone] that the User left the forwarding step.
 */
fun onboardingStep(
    signedIn: Boolean,
    profile: Profile?,
    userMobile: String?,
    numberSeen: Boolean,
    forwardingDone: Boolean,
): OnboardingStep {
    val missing = missingPrerequisites(signedIn, profile, userMobile)
    return when {
        AgentPrerequisite.SIGN_IN in missing -> OnboardingStep.SIGN_IN
        AgentPrerequisite.CONSENT in missing -> OnboardingStep.CONSENT
        AgentPrerequisite.NUMBER in missing || !numberSeen -> OnboardingStep.NUMBER
        AgentPrerequisite.USER_MOBILE in missing -> OnboardingStep.MOBILE
        !forwardingDone -> OnboardingStep.FORWARDING
        else -> OnboardingStep.SETTINGS
    }
}

/** Settings document for the first upload: the cached local settings, with the Agent off and without the server-owned version. */
fun initialUploadDocument(local: Settings): JsonObject {
    val json = CoreJson.encodeToJsonElement(Settings.serializer(), local.copy(agentEnabled = false)) as JsonObject
    return JsonObject(json.filterKeys { it != "version" })
}

/** Offer the upload only when the server holds no document (version 0) and the local settings differ from the defaults. */
fun shouldOfferUpload(serverVersion: Long?, local: Settings): Boolean =
    serverVersion == 0L && initialUploadDocument(local) != initialUploadDocument(Settings())
