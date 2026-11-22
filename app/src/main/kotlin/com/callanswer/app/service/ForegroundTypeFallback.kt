package com.callanswer.app.service

/**
 * Decides how the call service enters the foreground. The `microphone` type is a while-in-use type and is denied
 * when started from the background; that denial is not a failed start. The service then stays foreground as
 * `mediaPlayback` only. Only when both attempts fail is the start a failure.
 */
object ForegroundTypeFallback {
    enum class Result { MEDIA_AND_MIC, MEDIA_ONLY, FAILED }

    /** True for the exceptions the platform throws when a foreground service type is not allowed. */
    fun isTypeDenial(e: Throwable): Boolean =
        e is SecurityException || e is IllegalStateException ||
            e.javaClass.simpleName.contains("ForegroundService")

    /**
     * [tryMic] and [tryMedia] each attempt `startForeground` with the matching types and return true on success.
     * Either may throw a type-denial exception, which counts as false.
     */
    fun enter(micAllowed: Boolean, tryMic: () -> Boolean, tryMedia: () -> Boolean): Result {
        if (micAllowed && attempt(tryMic)) return Result.MEDIA_AND_MIC
        return if (attempt(tryMedia)) Result.MEDIA_ONLY else Result.FAILED
    }

    private fun attempt(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: RuntimeException) {
        if (!isTypeDenial(e)) throw e
        false
    }
}
