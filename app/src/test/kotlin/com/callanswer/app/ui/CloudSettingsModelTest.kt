package com.callanswer.app.ui

import com.callanswer.app.api.ApiException
import com.callanswer.app.api.SettingsDocument
import com.callanswer.app.data.SyncResult
import com.callanswer.app.ui.screens.AgentDraft
import com.callanswer.app.ui.screens.AgentDraftResult
import com.callanswer.app.ui.screens.AgentField
import com.callanswer.app.ui.screens.ConsentStatus
import com.callanswer.app.ui.screens.EditOutcome
import com.callanswer.app.ui.screens.agentSwitchEnabled
import com.callanswer.app.ui.screens.changedFields
import com.callanswer.app.ui.screens.disclosureOffWarning
import com.callanswer.app.ui.screens.settingsFromJson
import com.callanswer.app.ui.screens.toOutcome
import com.callanswer.core.model.FallbackRoute
import com.callanswer.core.model.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSettingsModelTest {
    private val base = Settings()

    @Test fun changedFieldsListsOnlyDifferencesAndNeverTheVersion() {
        val changed = changedFields(base, base.copy(agentEnabled = true, retentionDays = 90, version = 7))
        assertEquals(setOf("agentEnabled", "retentionDays"), changed.keys)
        assertEquals(JsonPrimitive(true), changed["agentEnabled"])
    }

    @Test fun unreadableDocumentFallsBackToDefaults() {
        assertEquals(base, settingsFromJson(null))
        assertEquals(base, settingsFromJson(JsonObject(mapOf("fallbackRoute" to JsonPrimitive("NOPE")))))
        assertEquals(60, settingsFromJson(JsonObject(mapOf("retentionDays" to JsonPrimitive(60)))).retentionDays)
    }

    @Test fun agentDraftRejectsBadValuesWithAllowedRanges() {
        val r = AgentDraft.from(base).copy(
            userMobile = "9876", retentionDays = "366", exotelPaise = "-1", monthlyLimitInr = "x",
        ).applyTo(base) as AgentDraftResult.Invalid
        assertEquals(
            setOf(AgentField.USER_MOBILE, AgentField.RETENTION, AgentField.RATE_EXOTEL, AgentField.MONTHLY_LIMIT),
            r.errors.keys,
        )
        assertTrue(r.errors.getValue(AgentField.RETENTION).contains("365"))
    }

    @Test fun agentDraftAcceptsValidValuesAndBlankClears() {
        val draft = AgentDraft.from(base).copy(
            userMobile = " +919876543210 ", greetingName = " ", retentionDays = "45", sonicInPaise = "12",
            monthlyLimitInr = "", fallbackRoute = FallbackRoute.FORWARD_TO_MOBILE, recordingEnabled = false,
        )
        val s = (draft.applyTo(base.copy(monthlyLimitInr = 500)) as AgentDraftResult.Ok).settings
        assertEquals("+919876543210", s.userMobile)
        assertEquals(null, s.greetingName)
        assertEquals(45, s.retentionDays)
        assertEquals(12L, s.costRates.sonicInputPerMinutePaise)
        assertEquals(null, s.monthlyLimitInr)
        assertEquals(FallbackRoute.FORWARD_TO_MOBILE, s.fallbackRoute)
        assertFalse(s.recordingEnabled)
    }

    @Test fun agentSwitchNeedsConsentToTurnOnButCanAlwaysTurnOff() {
        assertTrue(agentSwitchEnabled(ConsentStatus.Accepted, false))
        assertFalse(agentSwitchEnabled(ConsentStatus.NotAccepted, false))
        assertFalse(agentSwitchEnabled(ConsentStatus.Loading, false))
        assertTrue(agentSwitchEnabled(ConsentStatus.NotAccepted, true))
    }

    @Test fun disclosureWarningOnlyWhileNoticeIsOffAndCallsAreRecorded() {
        assertTrue(disclosureOffWarning(base.copy(disclosureEnabled = false, recordingEnabled = true)))
        assertFalse(disclosureOffWarning(base.copy(disclosureEnabled = true, recordingEnabled = true)))
        assertFalse(disclosureOffWarning(base.copy(disclosureEnabled = false, recordingEnabled = false)))
    }

    @Test fun syncResultsMapToOutcomes() {
        val doc = SettingsDocument(1, JsonObject(emptyMap()))
        assertEquals(EditOutcome.Applied, SyncResult.Saved(doc).toOutcome())
        assertEquals(EditOutcome.NotYetApplied, SyncResult.Failed(ApiException.Network()).toOutcome())
        assertEquals(EditOutcome.NotYetApplied, SyncResult.RetriesExhausted.toOutcome())
        assertEquals(EditOutcome.NeedsChoice, SyncResult.NeedsResolution(emptyList(), doc).toOutcome())
        assertEquals(EditOutcome.Rejected("userMobile", "m"), SyncResult.Invalid("userMobile", "m").toOutcome())
    }
}
