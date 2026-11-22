package com.callanswer.app.audio

/**
 * Decides what to do when the accessibility-owned `AudioRecord` returns zeros or is silenced after it had
 * delivered audio (S2c: RMS dropped to ~0 after 6 s). After [hadAudioMs] of audio: bad for [restartAfterMs] means
 * recreate the record once; bad for [giveUpMs] in total means assisted mode. Audio coming back resets the run.
 */
class LateSilenceTracker(
    private val hadAudioMs: Long = 2_000,
    private val restartAfterMs: Long = 2_000,
    private val giveUpMs: Long = 10_000,
    private val frameMs: Long = 32,
) {
    enum class Action { NONE, RESTART, ASSISTED }

    private var audioMs = 0L
    private var badSince = -1L
    private var restarted = false
    private var gaveUp = false

    val hadAudio: Boolean get() = audioMs >= hadAudioMs
    val restartedOnce: Boolean get() = restarted

    fun onFrame(nowMs: Long, nonZero: Boolean, silenced: Boolean): Action {
        val bad = !nonZero || silenced
        if (!bad) {
            audioMs += frameMs
            badSince = -1
            gaveUp = false
            return Action.NONE
        }
        if (!hadAudio) return Action.NONE
        if (badSince < 0) badSince = nowMs
        val badMs = nowMs - badSince
        if (badMs >= giveUpMs) {
            if (!gaveUp) { gaveUp = true; return Action.ASSISTED }
            return Action.NONE
        }
        if (!restarted && badMs >= restartAfterMs) { restarted = true; return Action.RESTART }
        return Action.NONE
    }

    fun reset() { audioMs = 0; badSince = -1; restarted = false; gaveUp = false }
}
