package com.callanswer.app.audio

import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.sqrt

/** Frame-level speech detector. Works on fixed windows of [WINDOW_SAMPLES] samples (16 kHz, floats in -1..1). */
interface VoiceActivityDetector {
    /** True when the window contains speech. */
    fun isSpeech(window: FloatArray): Boolean
    fun release()

    companion object {
        const val WINDOW_SAMPLES = 512
    }
}

/** Silero VAD run through sherpa-onnx. The model file is loaded per call and released with [release]. */
class SileroVad(modelFile: File, threshold: Float = 0.5f) : VoiceActivityDetector {
    private val vad: Vad

    init {
        val silero = SileroVadModelConfig()
        silero.model = modelFile.absolutePath
        silero.threshold = threshold
        silero.minSilenceDuration = 0.1f
        silero.minSpeechDuration = 0.1f
        silero.windowSize = VoiceActivityDetector.WINDOW_SAMPLES
        val cfg = VadModelConfig()
        cfg.sileroVadModelConfig = silero
        cfg.sampleRate = 16000
        cfg.numThreads = 1
        cfg.provider = "cpu"
        vad = Vad(null, cfg)
    }

    override fun isSpeech(window: FloatArray): Boolean {
        vad.acceptWaveform(window)
        // Segments are built by SpeechSegmenter; drop sherpa's own queue so it does not grow.
        while (!vad.empty()) vad.pop()
        return vad.isSpeechDetected()
    }

    override fun release() = vad.release()
}

/** Fallback when the Silero model cannot be loaded: a window is speech when its RMS exceeds [rmsThreshold]. */
class EnergyVad(private val rmsThreshold: Float = 0.015f) : VoiceActivityDetector {
    override fun isSpeech(window: FloatArray): Boolean {
        if (window.isEmpty()) return false
        var sum = 0.0
        for (s in window) sum += s * s
        return sqrt(sum / window.size) > rmsThreshold
    }

    override fun release() = Unit
}

/** Returns a [SileroVad] when the model loads, otherwise an [EnergyVad]. */
fun createVoiceActivityDetector(modelFile: File?): VoiceActivityDetector {
    if (modelFile != null && modelFile.isFile) {
        try {
            return SileroVad(modelFile)
        } catch (t: Throwable) {
            Log.w("SileroVad", "Silero VAD unavailable, using energy VAD", t)
        }
    }
    return EnergyVad()
}
