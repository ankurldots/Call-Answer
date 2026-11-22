package com.callanswer.app.a11y

import com.callanswer.app.audio.LateSilenceTracker
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class A11yEnabledListTest {
    private val ours = "com.callanswer.app/com.callanswer.app.a11y.CallAudioA11yService"
    private val other = "com.other/com.other.Svc"

    @Test fun parseHandlesNullEmptyAndBlanks() {
        assertEquals(emptyList<String>(), A11yEnabledList.parse(null))
        assertEquals(emptyList<String>(), A11yEnabledList.parse(""))
        assertEquals(listOf("a/b", "c/d"), A11yEnabledList.parse(":a/b::c/d:"))
    }

    @Test fun addToEmptyAndPreservesOthers() {
        assertEquals(listOf(ours), A11yEnabledList.add(emptyList(), ours))
        assertEquals(listOf(other, ours), A11yEnabledList.add(listOf(other), ours))
    }

    @Test fun addDoesNotDuplicateShortOrUpperCaseForm() {
        val short = "com.callanswer.app/.a11y.CallAudioA11yService".replace("/.a11y", "/.a11y")
        assertEquals(listOf(ours), A11yEnabledList.add(listOf(ours), ours))
        assertTrue(A11yEnabledList.contains(listOf("com.callanswer.app/com.callanswer.app.A11y.CALLAUDIOA11YSERVICE"), ours))
        assertTrue(A11yEnabledList.contains(listOf("com.callanswer.app/.a11y.CallAudioA11yService"), ours))
        assertFalse(A11yEnabledList.contains(listOf(other), ours))
        assertEquals(listOf(short), A11yEnabledList.add(listOf(short), ours))
    }

    @Test fun removeKeepsOthersAndRemovesDuplicates() {
        assertEquals(listOf(other), A11yEnabledList.remove(listOf(other, ours, ours.uppercase()), ours))
        assertEquals(emptyList<String>(), A11yEnabledList.remove(listOf(ours), ours))
        assertEquals(emptyList<String>(), A11yEnabledList.remove(emptyList(), ours))
    }

    @Test fun accessibilityEnabledFlagOnlyZeroWhenNothingRemains() {
        assertEquals(0, A11yEnabledList.accessibilityEnabledAfterRemoval(emptyList()))
        assertEquals(1, A11yEnabledList.accessibilityEnabledAfterRemoval(listOf(other)))
        assertEquals("a:b", A11yEnabledList.join(listOf("a", "b")))
    }

    @Test fun startupDisablesUnlessCallActiveOrSessionRunning() {
        assertTrue(A11yPolicy.disableAtStartup(enabled = true, callActive = false, sessionRunning = false))
        assertTrue(A11yPolicy.disableAtStartup(enabled = true, callActive = null, sessionRunning = false))
        assertFalse(A11yPolicy.disableAtStartup(enabled = true, callActive = true, sessionRunning = false))
        assertFalse(A11yPolicy.disableAtStartup(enabled = true, callActive = false, sessionRunning = true))
        assertFalse(A11yPolicy.disableAtStartup(enabled = false, callActive = false, sessionRunning = false))
    }

    @Test fun callEndAlwaysDisables() {
        assertTrue(A11yPolicy.disableAtCallEnd(true))
        assertTrue(A11yPolicy.disableAtCallEnd(false))
    }

    @Test fun lateSilenceRestartsOnceThenGivesUpAfterTenSeconds() {
        val t = LateSilenceTracker()
        var now = 0L
        repeat(100) { now += 32; assertEquals(LateSilenceTracker.Action.NONE, t.onFrame(now, true, false)) }
        assertTrue(t.hadAudio)
        val actions = mutableListOf<LateSilenceTracker.Action>()
        repeat(400) { now += 32; t.onFrame(now, false, false).let { if (it != LateSilenceTracker.Action.NONE) actions += it } }
        assertEquals(listOf(LateSilenceTracker.Action.RESTART, LateSilenceTracker.Action.ASSISTED), actions)
    }

    @Test fun silenceBeforeAnyAudioDoesNotTrigger() {
        val t = LateSilenceTracker()
        var now = 0L
        repeat(500) { now += 32; assertEquals(LateSilenceTracker.Action.NONE, t.onFrame(now, false, true)) }
    }
}
