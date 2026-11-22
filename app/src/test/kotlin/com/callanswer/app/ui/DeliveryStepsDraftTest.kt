package com.callanswer.app.ui

import com.callanswer.app.ui.screens.DeliveryStepsDraft
import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Language
import com.callanswer.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryStepsDraftTest {
    private val base = Settings()

    @Test fun oneLanguageOnlyIsSavedWithMissingLanguageWarning() {
        val r = DeliveryStepsDraft.applyTo(base, listOf(DeliveryStep("a", "Ring the bell.", "  ")))
        r as DeliveryStepsDraft.Result.Ok
        assertEquals(Language.HI, r.warnings.single().missing)
        assertEquals("", r.settings.deliverySteps.single().textHi)
    }

    @Test fun emptyListBlankStepTooLongAndTooManyAreRejectedWithLimitsMessage() {
        val long = "x".repeat(201)
        val eleven = List(11) { DeliveryStep("$it", "t", "t") }
        listOf(
            emptyList(),
            listOf(DeliveryStep("a", " ", "")),
            listOf(DeliveryStep("a", long, "")),
            eleven,
        ).forEach {
            val r = DeliveryStepsDraft.applyTo(base, it)
            assertTrue(r is DeliveryStepsDraft.Result.Invalid)
            assertTrue((r as DeliveryStepsDraft.Result.Invalid).message.contains("1 to 10"))
        }
    }

    @Test fun moveReordersAndIgnoresOutOfRange() {
        assertEquals(listOf(2, 1, 3), DeliveryStepsDraft.move(listOf(1, 2, 3), 1, -1))
        assertEquals(listOf(1, 2, 3), DeliveryStepsDraft.move(listOf(1, 2, 3), 0, -1))
    }

    @Test fun sampleTextSpeaksStepsInOrderWithMapLocation() {
        val steps = listOf(DeliveryStep("a", "Go to {map_location}.", ""), DeliveryStep("b", "Ring the bell.", "घंटी बजाइए।"))
        assertEquals("Go to Home. Ring the bell.", DeliveryStepsDraft.sampleText(steps, Language.EN, "Home"))
        assertEquals("Go to {map_location}. घंटी बजाइए।".replace("{map_location}", "Home"), DeliveryStepsDraft.sampleText(steps, Language.HI, "Home"))
    }
}
