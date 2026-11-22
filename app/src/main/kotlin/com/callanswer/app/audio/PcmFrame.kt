package com.callanswer.app.audio

/** One block of 16 kHz mono PCM16 captured from the microphone. [timestampMs] is `SystemClock.elapsedRealtime()` at read time. */
class PcmFrame(val samples: ShortArray, val timestampMs: Long) {
    override fun toString() = "PcmFrame(n=${samples.size}, t=$timestampMs)"
}
