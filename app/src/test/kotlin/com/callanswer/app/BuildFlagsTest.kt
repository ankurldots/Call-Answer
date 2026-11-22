package com.callanswer.app

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildFlagsTest {
    @Test
    fun asrEngineIsKnownValue() {
        assertTrue(BuildConfig.ASR_ENGINE in setOf("WHISPER_BASE_INT8", "VOSK_STREAMING"))
    }
}
