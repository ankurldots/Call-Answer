package com.callanswer.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import com.callanswer.core.echo.AgentWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

/** Plays Agent PCM. `stop()` must take effect within 500 ms. */
interface AgentAudioPlayer {
    suspend fun play(pcm: ShortArray, rate: Int, id: String)
    fun stop()
}

/**
 * [AgentAudioPlayer] backed by a low-latency [AudioTrack]. Speech is split into chunks of at most
 * [MAX_CHUNK_MS] with [LISTEN_GAP_MS] gaps. Each chunk's start and end come from
 * [AudioTrack.getTimestamp] and are published to [windows] and [gate] as Agent windows.
 * [usages] is tried in order until one initialises (S3 fixes the order).
 */
class AgentAudioPlayerImpl(
    private val gate: EchoGate,
    private val usages: List<Int> = listOf(
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_ASSISTANT,
        AudioAttributes.USAGE_MEDIA,
    ),
) : AgentAudioPlayer {
    private val _windows = MutableSharedFlow<AgentWindow>(extraBufferCapacity = 64)
    val windows: SharedFlow<AgentWindow> = _windows

    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false

    override suspend fun play(pcm: ShortArray, rate: Int, id: String) {
        stop()
        stopped = false
        withContext(Dispatchers.IO) {
            val chunkSamples = rate * MAX_CHUNK_MS / 1000
            var offset = 0
            while (offset < pcm.size && !stopped) {
                val end = minOf(pcm.size, offset + chunkSamples)
                playChunk(pcm, offset, end, rate)
                offset = end
                if (offset < pcm.size && !stopped) delay(LISTEN_GAP_MS)
            }
        }
    }

    private suspend fun playChunk(pcm: ShortArray, from: Int, to: Int, rate: Int) {
        val t = createTrack(rate, (to - from) * 2) ?: return
        track = t
        val total = to - from
        val startWall = SystemClock.elapsedRealtime()
        var startMs = -1L
        try {
            t.write(pcm, from, total)
            t.play()
            while (!stopped && t.playbackHeadPosition < total) {
                if (startMs < 0) startMs = timestampMs(t, startWall)
                delay(POLL_MS)
            }
            if (startMs < 0) startMs = startWall
            val endMs = if (stopped) SystemClock.elapsedRealtime() else startMs + total * 1000L / rate
            publish(startMs, maxOf(endMs, startMs))
        } catch (e: CancellationException) {
            stop()
            throw e
        } finally {
            if (track === t) track = null
            runCatching { t.release() }
        }
    }

    /** Wall-clock (elapsedRealtime) time at which the first frame was presented, if known. */
    private fun timestampMs(t: AudioTrack, fallback: Long): Long {
        val ts = AudioTimestamp()
        if (!t.getTimestamp(ts) || ts.framePosition < 0) return -1L
        // AudioTimestamp.nanoTime is CLOCK_MONOTONIC; convert to the elapsedRealtime base.
        val offset = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
        val rate = t.sampleRate
        val firstFrameNs = ts.nanoTime - ts.framePosition * 1_000_000_000L / rate
        return (firstFrameNs / 1_000_000L + offset).coerceAtLeast(fallback - 200)
    }

    private fun publish(startMs: Long, endMs: Long) {
        gate.markPlayback(startMs, endMs)
        _windows.tryEmit(AgentWindow(startMs, endMs))
    }

    /** Plays a short alert tone; marked as an Agent window so it is never transcribed. */
    suspend fun playAlertTone(pcm: ShortArray, rate: Int) = play(pcm, rate, "alert-tone")

    private fun createTrack(rate: Int, bytes: Int): AudioTrack? {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        for (usage in usages) {
            val t = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(bytes, minBuf))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            }.getOrNull()
            if (t != null && t.state == AudioTrack.STATE_INITIALIZED) return t
            t?.release()
        }
        return null
    }

    /** Pause plus flush, well inside 500 ms. */
    override fun stop() {
        stopped = true
        val t = track ?: return
        runCatching {
            t.pause()
            t.flush()
        }
    }

    companion object {
        const val MAX_CHUNK_MS = 8_000
        const val LISTEN_GAP_MS = 400L
        private const val POLL_MS = 10L
    }
}
