package com.callanswer.app.screening

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonContactSilencerTest {
    private val contacts = listOf("+91 98765-43210", "(022) 2345 6789", "+1 (415) 555-0100")
    private var unavailable = 0

    private fun silencer(
        on: Boolean = true,
        source: ContactNumberSource = ContactNumberSource { contacts },
        repeat: Boolean = false,
    ) = NonContactSilencer({ on }, source, { repeat }, { unavailable++ })

    private fun silence(s: NonContactSilencer, number: String?) = runBlocking { s.shouldSilence(number) }

    @Test fun matchesAcrossFormattingAndCountryCode() {
        for (n in listOf("9876543210", "09876543210", "+919876543210", "919876543210", "98765 43210", "+91-98765-43210", "+14155550100", "+1.415.555.0100")) {
            assertTrue(n, ContactMatcher.isKnown(n, contacts))
        }
    }

    @Test fun differentNumberDoesNotMatch() {
        assertFalse(ContactMatcher.isKnown("9876543211", contacts))
        assertFalse(ContactMatcher.isKnown("+14155550101", contacts))
    }

    @Test fun hiddenNumberNeverMatches() {
        assertFalse(ContactMatcher.isKnown(null, contacts))
        assertFalse(ContactMatcher.isKnown("  ", contacts))
        assertFalse(ContactMatcher.isKnown(null, listOf("")))
    }

    @Test fun unparsableNumberMatchesOnlySameDigits() {
        assertTrue(ContactMatcher.isKnown("56767", listOf("56-767")))
        assertFalse(ContactMatcher.isKnown("56767", contacts))
    }

    @Test fun nonContactIsSilencedWhenOn() {
        assertTrue(silence(silencer(), "+919000000000"))
    }

    @Test fun contactIsNeverSilenced() {
        assertFalse(silence(silencer(), "09876543210"))
        assertFalse(silence(silencer(repeat = true), "09876543210"))
    }

    @Test fun nothingIsSilencedWhenOff() {
        var read = false
        val s = silencer(on = false, source = { read = true; contacts })
        assertFalse(silence(s, "+919000000000"))
        assertFalse(read)
        assertEquals(0, unavailable)
    }

    @Test fun repeatCallerIsNotSilenced() {
        assertFalse(silence(silencer(repeat = true), "+919000000000"))
    }

    @Test fun hiddenNumberIsSilenced() {
        assertTrue(silence(silencer(), null))
    }

    @Test fun missingPermissionLeavesRingerOnAndNotifies() {
        val s = silencer(source = { null })
        assertFalse(silence(s, "+919000000000"))
        assertFalse(silence(s, null))
        assertEquals(2, unavailable)
    }

    @Test fun failedLookupLeavesRingerOnAndNotifies() {
        val s = silencer(source = { throw IllegalStateException("boom") })
        assertFalse(silence(s, "+919000000000"))
        assertEquals(1, unavailable)
    }

    @Test fun failedRepeatCheckStillSilencesNonContact() {
        val s = NonContactSilencer({ true }, { contacts }, { throw IllegalStateException() }, { unavailable++ })
        assertTrue(silence(s, "+919000000000"))
    }
}
