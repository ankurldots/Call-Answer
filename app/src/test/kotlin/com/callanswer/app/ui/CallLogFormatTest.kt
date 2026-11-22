package com.callanswer.app.ui

import com.callanswer.app.ui.screens.CallLogFormat
import com.callanswer.core.model.ReportStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogFormatTest {
    @Test
    fun retryOnlyForFailed() {
        assertTrue(CallLogFormat.showRetry(ReportStatus.FAILED))
        ReportStatus.entries.filter { it != ReportStatus.FAILED }.forEach { assertFalse(CallLogFormat.showRetry(it)) }
    }

    @Test
    fun dateTimeUsesEntryZone() {
        assertEquals("1 Jan 1970, 05:30:00 IST", CallLogFormat.dateTime(0L, "Asia/Kolkata"))
    }

    @Test
    fun labelIsReadable() {
        assertEquals("delivery done", CallLogFormat.label(com.callanswer.core.model.EndReason.DELIVERY_DONE))
    }
}
