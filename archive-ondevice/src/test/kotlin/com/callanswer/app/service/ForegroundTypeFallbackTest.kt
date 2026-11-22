package com.callanswer.app.service

import com.callanswer.app.service.ForegroundTypeFallback.Result
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

class ForegroundTypeFallbackTest {
    @Test fun micAllowedUsesBothTypes() =
        assertEquals(Result.MEDIA_AND_MIC, ForegroundTypeFallback.enter(true, { true }, { error("unused") }))

    @Test fun micDeniedFallsBackToMediaAndCountsAsStarted() {
        var mediaTried = false
        val r = ForegroundTypeFallback.enter(true, { throw SecurityException("microphone denied") }, { mediaTried = true; true })
        assertEquals(Result.MEDIA_ONLY, r)
        assertEquals(true, mediaTried)
    }

    @Test fun micReturningFalseFallsBackToMedia() =
        assertEquals(Result.MEDIA_ONLY, ForegroundTypeFallback.enter(true, { false }, { true }))

    @Test fun noRecordAudioSkipsMic() =
        assertEquals(Result.MEDIA_ONLY, ForegroundTypeFallback.enter(false, { error("unused") }, { true }))

    @Test fun bothDeniedIsFailed() =
        assertEquals(Result.FAILED, ForegroundTypeFallback.enter(true, { throw SecurityException() }, { throw SecurityException() }))

    @Test fun unrelatedExceptionPropagates() {
        assertThrows(IllegalArgumentException::class.java) {
            ForegroundTypeFallback.enter(true, { throw IllegalArgumentException() }, { true })
        }
    }
}
