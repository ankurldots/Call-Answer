package com.callanswer.app.asr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.callanswer.app.audio.SpeechSegment
import com.callanswer.core.model.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Last resort: the Android `SpeechRecognizer` fed from our PCM through `EXTRA_AUDIO_SOURCE` (Android 13+).
 * Below Android 13 it returns an empty result. Prefers the on-device recognizer when available.
 */
class SystemRecognizer(private val context: Context) : SpeechRecognizerPort {

    override suspend fun transcribe(segment: SpeechSegment, languageHint: Language?): AsrResult {
        val t0 = SystemClock.elapsedRealtime()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !SpeechRecognizer.isRecognitionAvailable(context)) {
            return AsrResult("", null, 0)
        }
        val text = withContext(Dispatchers.Main) { recognize(segment, languageHint) }
        return AsrResult(text, languageHint, SystemClock.elapsedRealtime() - t0)
    }

    private suspend fun recognize(segment: SpeechSegment, hint: Language?): String =
        suspendCancellableCoroutine { cont ->
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)

            fun finish(result: String) {
                runCatching { recognizer.destroy() }
                runCatching { readSide.close() }
                runCatching { writeSide.close() }
                if (cont.isActive) cont.resume(result)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onError(error: Int) = finish("")
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            cont.invokeOnCancellation { finish("") }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (hint == Language.EN) "en-IN" else "hi-IN")
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            }
            recognizer.startListening(intent)

            Thread({
                runCatching {
                    FileOutputStream(writeSide.fileDescriptor).use { out ->
                        val bytes = ByteArray(segment.samples.size * 2)
                        for (i in segment.samples.indices) {
                            bytes[2 * i] = segment.samples[i].toInt().toByte()
                            bytes[2 * i + 1] = (segment.samples[i].toInt() shr 8).toByte()
                        }
                        out.write(bytes)
                    }
                }
                runCatching { writeSide.close() }
            }, "system-asr-feed").start()
        }

    override fun release() = Unit
}
