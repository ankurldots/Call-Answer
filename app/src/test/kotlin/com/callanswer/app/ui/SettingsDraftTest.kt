package com.callanswer.app.ui

import com.callanswer.app.ui.screens.DraftResult
import com.callanswer.app.ui.screens.GatesDraft
import com.callanswer.app.ui.screens.GeneralDraft
import com.callanswer.app.ui.screens.SettingsField
import com.callanswer.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDraftTest {
    private val base = Settings()

    @Test fun invalidTransferAndStallingAreReported() {
        val r = GeneralDraft.from(base).copy(transferTarget = "12ab", maxStalling = "5").applyTo(base)
        val errors = (r as DraftResult.Invalid).errors
        assertTrue(SettingsField.TRANSFER_TARGET in errors && SettingsField.MAX_STALLING in errors)
    }

    @Test fun validGeneralDraftClearsBlankTransferAndSplitsTerms() {
        val r = GeneralDraft.from(base).copy(maxStalling = " 90 ", protectedTerms = "a, b\na").applyTo(base)
        val s = (r as DraftResult.Ok).settings
        assertEquals(90, s.maxStallingSeconds)
        assertEquals(null, s.transferTarget)
        assertEquals(listOf("a", "b"), s.protectedTerms)
    }

    @Test fun equalNightTimesAreRejectedOnEnd() {
        val r = GatesDraft.from(base).copy(nightStart = "10:00", nightEnd = "10:00").applyTo(base)
        assertTrue(SettingsField.NIGHT_END in (r as DraftResult.Invalid).errors)
    }

    @Test fun emptyGateFieldIsRejected() {
        val d = GatesDraft.from(base)
        val r = d.copy(gate1 = d.gate1.copy(second = " ")).applyTo(base)
        assertTrue(SettingsField.GATE1_LOCATION in (r as DraftResult.Invalid).errors)
    }
}
