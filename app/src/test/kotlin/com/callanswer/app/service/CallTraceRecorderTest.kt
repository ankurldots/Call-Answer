package com.callanswer.app.service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class CallTraceRecorderTest {
    private data class Ev(val x: Int)
    @Test fun exportListsEntriesWithDeltas() {
        val r = CallTraceRecorder()
        r.event(100, Ev(1)); r.effect(350, Ev(2))
        val lines = r.export("s1").lines().filter { it.isNotBlank() }
        assertEquals(4, lines.size)
        assertTrue(lines[2].startsWith("100\t100\tEVENT\tEv"))
        assertTrue(lines[3].startsWith("350\t250\tEFFECT\tEv"))
    }
    @Test fun clearEmptiesTrace() {
        val r = CallTraceRecorder(); r.event(1, Ev(1)); r.clear()
        assertTrue(r.snapshot().isEmpty())
    }
}
