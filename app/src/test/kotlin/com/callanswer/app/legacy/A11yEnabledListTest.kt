package com.callanswer.app.legacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class A11yEnabledListTest {
    private val ours = LegacyA11yCleanup.LEGACY_COMPONENT
    private val other = "com.other/com.other.Svc"
    @Test fun parseHandlesNullEmptyAndBlanks() {
        assertEquals(emptyList<String>(), A11yEnabledList.parse(null))
        assertEquals(emptyList<String>(), A11yEnabledList.parse(""))
        assertEquals(listOf("a/b", "c/d"), A11yEnabledList.parse(":a/b::c/d:"))
    }
    @Test fun containsMatchesShortAndUpperCaseForms() {
        assertTrue(A11yEnabledList.contains(listOf(ours), ours))
        assertTrue(A11yEnabledList.contains(listOf("com.callanswer.app/com.callanswer.app.A11y.CALLAUDIOA11YSERVICE"), ours))
        assertTrue(A11yEnabledList.contains(listOf("com.callanswer.app/.a11y.CallAudioA11yService"), ours))
        assertFalse(A11yEnabledList.contains(listOf(other), ours))
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
}
