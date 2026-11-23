package com.callanswer.app.screening

import android.telecom.TelecomManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningWiringTest {
    private class MemoryStore : BooleanStore {
        val map = HashMap<String, Boolean>()
        override fun get(key: String, default: Boolean) = map[key] ?: default
        override fun set(key: String, value: Boolean) { map[key] = value }
    }

    @Test fun settingDefaultsToOffAndPersistsChange() {
        val settings = LocalScreeningSettings(MemoryStore())
        assertFalse(settings.silenceNonContacts)
        settings.silenceNonContacts = true
        assertTrue(settings.silenceNonContacts)
    }

    @Test fun hiddenOrNonIncomingNumberIsNull() {
        val allowed = TelecomManager.PRESENTATION_ALLOWED
        assertEquals("+919000000000", ScreeningNumbers.of(true, allowed, "+919000000000"))
        assertNull(ScreeningNumbers.of(true, TelecomManager.PRESENTATION_RESTRICTED, "+919000000000"))
        assertNull(ScreeningNumbers.of(true, TelecomManager.PRESENTATION_UNKNOWN, "+919000000000"))
        assertNull(ScreeningNumbers.of(true, allowed, "  "))
        assertNull(ScreeningNumbers.of(true, allowed, null))
        assertNull(ScreeningNumbers.of(false, allowed, "+919000000000"))
    }

    @Test fun silenceDecisionIsPassedThrough() {
        assertTrue(runBlocking { silenceWithin(1_000) { true } })
        assertFalse(runBlocking { silenceWithin(1_000) { false } })
    }

    @Test fun timeoutLeavesRingerOn() {
        assertFalse(runBlocking { silenceWithin(50) { delay(5_000); true } })
    }

    @Test fun failureLeavesRingerOn() {
        assertFalse(runBlocking { silenceWithin(1_000) { throw IllegalStateException() } })
    }

    @Test fun contactNumbersNullWithoutPermissionAndNoQuery() {
        var queried = false
        val source = AndroidContactNumbers({ false }, { queried = true; listOf("1") })
        assertNull(runBlocking { source.numbers() })
        assertFalse(queried)
    }

    @Test fun contactNumbersReturnQueryResult() {
        val source = AndroidContactNumbers({ true }, { listOf("+91 98765-43210") })
        assertEquals(listOf("+91 98765-43210"), runBlocking { source.numbers() })
    }

    @Test fun contactNumbersNullWhenQueryFails() {
        assertNull(runBlocking { AndroidContactNumbers({ true }, { throw SecurityException() }).numbers() })
        assertNull(runBlocking { AndroidContactNumbers({ true }, { null }).numbers() })
    }
}
