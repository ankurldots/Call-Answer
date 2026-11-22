package com.callanswer.app.ui

import com.callanswer.app.ui.screens.CallDetailFormat
import com.callanswer.app.ui.screens.TimelineEvent
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.SonicSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallDetailFormatTest {
    @Test fun inrFormatsPaise() {
        assertEquals("₹12.50", CallDetailFormat.inr(1250))
        assertEquals("₹0.05", CallDetailFormat.inr(5))
    }

    @Test fun secondsRoundsToOneDecimal() {
        assertEquals("1.3 s", CallDetailFormat.seconds(1250))
    }

    @Test fun limitFractionIsCappedAndNullWithoutLimit() {
        assertNull(CallDetailFormat.limitFraction(100, null))
        assertEquals(0.5f, CallDetailFormat.limitFraction(5000, 100)!!, 0.001f)
        assertEquals(1f, CallDetailFormat.limitFraction(50000, 100)!!, 0.001f)
    }

    @Test fun timelineIsSortedAndEndsWithCallEnd() {
        val base = CoreJson.decodeFromString(
            CallLogEntry.serializer(),
            """{"id":"c","callerNumber":null,"startedAtEpochMs":1000,"zoneId":"UTC","durationSec":10,"language":null,
            "intent":"UNKNOWN","outcome":"CALLER_HUNG_UP","endReason":"CALLER_HUNG_UP","transcript":[],"gateStatements":[],
            "voiceGender":"FEMALE","voiceSubstitutions":[],"stallingDurationSec":null,"takeovers":[],"interruption":null,
            "recording":{"status":"NOT_FOUND"},"notes":[]}""",
        )
        val e = base.copy(sonicSessions = listOf(SonicSessionRecord(500, 9000, "renewal"), SonicSessionRecord(0, 400)))
        val t = CallDetailFormat.timeline(e)
        assertEquals(listOf(0L, 400L, 500L, 9000L, 10000L), t.map { it.tMs })
        assertEquals(TimelineEvent.Kind.CALL_END, t.last().kind)
    }
}
