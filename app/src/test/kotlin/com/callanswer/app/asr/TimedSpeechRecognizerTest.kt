package com.callanswer.app.asr

import com.callanswer.app.audio.SpeechSegment
import com.callanswer.core.model.Language
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TimedSpeechRecognizerTest {
    private val segment = SpeechSegment(0, 100, ShortArray(1600))

    private class Fake(val delayMs: Long, val text: String) : SpeechRecognizerPort {
        var released = false
        override suspend fun transcribe(segment: SpeechSegment, languageHint: Language?): AsrResult {
            delay(delayMs)
            return AsrResult(text, Language.EN, delayMs)
        }
        override fun release() { released = true }
    }

    @Test
    fun returnsPrimaryResultWithinLimit() = runBlocking {
        val r = TimedSpeechRecognizer(Fake(10, "hello"), null, timeoutMs = 500)
        assertEquals("hello", r.transcribe(segment, null).text)
    }

    @Test
    fun timeoutGivesEmptyResult() = runBlocking {
        val r = TimedSpeechRecognizer(Fake(2000, "late"), null, timeoutMs = 50)
        assertEquals("", r.transcribe(segment, null).text)
    }

    @Test
    fun releaseReleasesBoth() {
        val a = Fake(0, ""); val b = Fake(0, "")
        TimedSpeechRecognizer(a, b).release()
        assertEquals(true, a.released && b.released)
    }
}
