package com.callanswer.app.asr

import android.content.Context
import android.util.Log
import com.callanswer.app.BuildConfig
import com.callanswer.app.audio.SpeechSegment
import com.callanswer.core.model.Language
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The recognizer the engine uses: the S7-chosen [primary] with a time limit ([timeoutMs], 3 s), and [lastResort]
 * when the primary cannot run (model missing or failing). A timeout gives an empty result, which the
 * conversation engine treats as "ask once to repeat" (2.10). Models are released with [release] at call end.
 */
class TimedSpeechRecognizer(
    private val primary: SpeechRecognizerPort,
    private val lastResort: SpeechRecognizerPort?,
    private val timeoutMs: Long = ASR_TIMEOUT_MS,
) : SpeechRecognizerPort {

    override suspend fun transcribe(segment: SpeechSegment, languageHint: Language?): AsrResult {
        val t0 = nowMs()
        val result = try {
            withTimeoutOrNull(timeoutMs) { primary.transcribe(segment, languageHint) }
                ?: return AsrResult("", null, nowMs() - t0)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Primary recognizer failed, trying last resort", t)
            val fallback = lastResort ?: return AsrResult("", null, nowMs() - t0)
            val remaining = (timeoutMs - (nowMs() - t0)).coerceAtLeast(1)
            return try {
                withTimeoutOrNull(remaining) { fallback.transcribe(segment, languageHint) }
                    ?: AsrResult("", null, nowMs() - t0)
            } catch (e: CancellationException) {
                throw e
            } catch (t2: Throwable) {
                Log.w(TAG, "Last-resort recognizer failed", t2)
                AsrResult("", null, nowMs() - t0)
            }
        }
        return result
    }

    override fun release() {
        primary.release()
        lastResort?.release()
    }

    companion object {
        const val ASR_TIMEOUT_MS = 3000L
        private const val TAG = "AsrEngine"
    }
}

/** Builds the recognizer for one call from `BuildConfig.ASR_ENGINE` (`WHISPER_BASE_INT8` or `VOSK_STREAMING`). */
fun createSpeechRecognizer(context: Context, engine: String = BuildConfig.ASR_ENGINE): SpeechRecognizerPort {
    val app = context.applicationContext
    val store = AsrModelStore(app)
    val primary: SpeechRecognizerPort =
        if (engine == "WHISPER_BASE_INT8") WhisperRecognizer(store) else VoskRecognizer(store)
    return TimedSpeechRecognizer(primary, SystemRecognizer(app))
}

private fun nowMs(): Long = System.nanoTime() / 1_000_000
