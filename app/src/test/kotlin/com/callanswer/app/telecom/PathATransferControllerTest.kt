package com.callanswer.app.telecom

import com.callanswer.core.engine.PathAResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PathATransferControllerTest {
    private var now = 0L

    private class Phone(val failPlace: Boolean = false) : TransferPhone {
        var placed = 0
        var ended = 0
        override fun placeCall(target: String) { if (failPlace) error("x"); placed++ }
        override fun endCall(): Boolean { ended++; return true }
    }

    private class Ui(val mergeAfterPolls: Int, val mergeClick: Boolean = true, val resume: Boolean = true) : TransferInCallUi {
        var polls = 0
        var merged = 0
        var resumed = 0
        override fun isMergeEnabled() = ++polls > mergeAfterPolls
        override fun clickMerge(): Boolean { merged++; return mergeClick }
        override fun clickSwapOrResume(): Boolean { resumed++; return resume }
    }

    private fun controller(phone: Phone, ui: Ui?) =
        PathATransferController(phone, { ui }, { now }, { now += it })

    @Test fun mergesWhenEnabled() = runBlocking {
        val phone = Phone(); val ui = Ui(mergeAfterPolls = 3)
        assertEquals(PathAResult.MERGED, controller(phone, ui).connect("100"))
        assertEquals(1, ui.merged); assertEquals(0, phone.ended)
    }

    @Test fun noAnswerEndsCallAndResumes() = runBlocking {
        val phone = Phone(); val ui = Ui(mergeAfterPolls = Int.MAX_VALUE)
        assertEquals(PathAResult.NO_ANSWER_30S, controller(phone, ui).connect("100"))
        assertEquals(1, phone.ended); assertEquals(1, ui.resumed)
    }

    @Test fun placeCallFailureIsFailed() = runBlocking {
        val phone = Phone(failPlace = true)
        assertEquals(PathAResult.FAILED, controller(phone, Ui(0)).connect("100"))
        assertEquals(0, phone.ended)
    }

    @Test fun mergeClickFailureEndsAndResumes() = runBlocking {
        val phone = Phone(); val ui = Ui(0, mergeClick = false)
        assertEquals(PathAResult.FAILED, controller(phone, ui).connect("100"))
        assertEquals(1, phone.ended); assertEquals(1, ui.resumed)
    }

    @Test fun resumeNotFoundIsFailed() = runBlocking {
        val ui = Ui(Int.MAX_VALUE, resume = false)
        assertEquals(PathAResult.FAILED, controller(Phone(), ui).connect("100"))
        assertEquals(PathATransferController.RESUME_ATTEMPTS, ui.resumed)
    }

    @Test fun serviceDisabledIsFailedBecauseResumeCannotBePressed() = runBlocking {
        assertEquals(PathAResult.FAILED, controller(Phone(), null).connect("100"))
    }
}
