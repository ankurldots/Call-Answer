package com.callanswer.app.ui

import com.callanswer.app.api.Profile
import com.callanswer.app.ui.screens.AgentPrerequisite
import com.callanswer.app.ui.screens.NUMBER_POLL_INTERVAL_MS
import com.callanswer.app.ui.screens.OnboardingStep
import com.callanswer.app.ui.screens.agentMayTurnOn
import com.callanswer.app.ui.screens.initialUploadDocument
import com.callanswer.app.ui.screens.isValidUserMobile
import com.callanswer.app.ui.screens.missingPrerequisites
import com.callanswer.app.ui.screens.onboardingStep
import com.callanswer.app.ui.screens.shouldOfferUpload
import com.callanswer.app.ui.screens.userMobileError
import com.callanswer.core.model.Settings
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingModelTest {
    private val number = "+918012345678"
    private val mobile = "+919876543210"
    private fun profile(consent: Boolean = true, assigned: String? = number) =
        Profile(userId = "u", virtualNumber = assigned, consentAccepted = consent)

    private fun step(signedIn: Boolean = true, p: Profile? = profile(), m: String? = mobile, seen: Boolean = true, fwd: Boolean = false) =
        onboardingStep(signedIn, p, m, seen, fwd)

    @Test fun stepsFollowTheSpecifiedOrder() {
        assertEquals(OnboardingStep.SIGN_IN, step(signedIn = false))
        assertEquals(OnboardingStep.CONSENT, step(p = profile(consent = false)))
        assertEquals(OnboardingStep.NUMBER, step(p = profile(assigned = null)))
        assertEquals(OnboardingStep.NUMBER, step(seen = false))
        assertEquals(OnboardingStep.MOBILE, step(m = null))
        assertEquals(OnboardingStep.FORWARDING, step())
        assertEquals(OnboardingStep.SETTINGS, step(fwd = true))
    }

    @Test fun unassignedNumberNeverReachesForwardingOrMobile() {
        assertEquals(OnboardingStep.NUMBER, step(p = profile(assigned = " "), fwd = true))
    }

    @Test fun pollIntervalIsAtMostSixtySeconds() = assertTrue(NUMBER_POLL_INTERVAL_MS <= 60_000L)

    @Test fun agentNeedsConsentNumberAndValidMobile() {
        assertTrue(agentMayTurnOn(true, profile(), mobile))
        assertFalse(agentMayTurnOn(true, profile(consent = false), mobile))
        assertFalse(agentMayTurnOn(true, profile(assigned = null), mobile))
        assertFalse(agentMayTurnOn(true, profile(), null))
        assertFalse(agentMayTurnOn(true, profile(), "9876543210"))
        assertFalse(agentMayTurnOn(false, profile(), mobile))
        assertEquals(
            listOf(AgentPrerequisite.CONSENT, AgentPrerequisite.NUMBER, AgentPrerequisite.USER_MOBILE),
            missingPrerequisites(true, profile(consent = false, assigned = null), ""),
        )
    }

    @Test fun userMobileIsValidatedAndMayNotBeTheVirtualNumber() {
        assertNull(userMobileError(mobile, number))
        assertNotNull(userMobileError("", number))
        assertNotNull(userMobileError("12345", number))
        assertNotNull(userMobileError(number, number))
        assertFalse(isValidUserMobile(number, number))
    }

    @Test fun uploadIsOfferedOnlyForVersionZeroWithLocalChanges() {
        val local = Settings(greetingName = "Asha")
        assertTrue(shouldOfferUpload(0L, local))
        assertFalse(shouldOfferUpload(3L, local))
        assertFalse(shouldOfferUpload(null, local))
        assertFalse(shouldOfferUpload(0L, Settings()))
    }

    @Test fun uploadDocumentKeepsTheAgentOffAndOmitsTheVersion() {
        val doc = initialUploadDocument(Settings(agentEnabled = true, version = 9, greetingName = "Asha"))
        assertFalse("version" in doc.keys)
        assertEquals(JsonPrimitive(false), doc["agentEnabled"])
        assertEquals(JsonPrimitive("Asha"), doc["greetingName"])
    }
}
