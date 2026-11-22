package com.callanswer.spike

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Plays bundled mono PCM16 WAV assets through an AudioTrack with a switchable usage. */
class AudioPlayer(private val context: Context) {
    private var track: AudioTrack? = null
    @Volatile private var playing = false

    val isPlaying: Boolean get() = playing

    /** Starts playback of [assetPath] (for example `s3/hi_01.wav`). Returns the clip length in ms. */
    fun play(assetPath: String, usage: Int, onDone: () -> Unit = {}): Long {
        stop()
        val wav = context.assets.open(assetPath).use { it.readBytes() }
        val (rate, pcm) = parseWav(wav)
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(pcm.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        val frames = pcm.size / 2
        t.notificationMarkerPosition = frames
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(track: AudioTrack?) {
                if (this@AudioPlayer.track === track) {
                    playing = false
                    onDone()
                }
            }
            override fun onPeriodicNotification(track: AudioTrack?) = Unit
        })
        track = t
        playing = true
        t.play()
        return frames * 1000L / rate
    }

    /** Pauses then flushes the track, then releases it. Returns the pause + flush time in ms, or -1 if idle. */
    fun stopMeasured(): Double {
        val t = track ?: return -1.0
        val start = System.nanoTime()
        t.pause()
        t.flush()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        playing = false
        t.release()
        track = null
        return ms
    }

    fun stop() {
        stopMeasured()
    }

    private fun parseWav(b: ByteArray): Pair<Int, ByteArray> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var rate = 16_000
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4)
            if (id == "fmt ") rate = bb.getInt(pos + 12)
            if (id == "data") {
                val len = minOf(size, b.size - pos - 8)
                return rate to b.copyOfRange(pos + 8, pos + 8 + len)
            }
            pos += 8 + size + (size and 1)
        }
        error("no data chunk")
    }

    companion object {
        /** Sets STREAM_VOICE_CALL volume to [percent] of its maximum. Returns "index/max". */
        fun setCallVolumePercent(context: Context, percent: Int): String {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            val idx = (max * percent / 100.0).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, idx, 0)
            return "$idx/$max"
        }
    }
}
