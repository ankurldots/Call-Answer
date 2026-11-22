package com.callanswer.app.recording

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Playback state exposed to the UI (12.8). */
enum class PlaybackState { IDLE, PLAYING, PAUSED, ENDED, ERROR }

/**
 * Plays a linked recording from its `content://` URI with Media3 ExoPlayer (12.8).
 * Only [ContentDataSource] is registered, so no network access is possible. An unreadable
 * or removed file ends in [PlaybackState.ERROR] (12.13). Call from the main thread.
 */
@OptIn(UnstableApi::class)
class PlaybackController(context: Context) {
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) _state.value = PlaybackState.ENDED
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = PlaybackState.ERROR
        }
    }

    /** Starts playback of [uri], replacing any current playback. */
    fun play(uri: Uri) {
        stop()
        val p = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DataSource.Factory { ContentDataSource(appContext) }))
            .build()
        p.addListener(listener)
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
        player = p
        _state.value = PlaybackState.PLAYING
    }

    fun pause() {
        val p = player ?: return
        if (_state.value != PlaybackState.PLAYING) return
        p.pause()
        _state.value = PlaybackState.PAUSED
    }

    fun resume() {
        val p = player ?: return
        when (_state.value) {
            PlaybackState.PAUSED -> p.play()
            PlaybackState.ENDED -> { p.seekTo(0); p.play() }
            else -> return
        }
        _state.value = PlaybackState.PLAYING
    }

    /** Stops playback and releases the player. */
    fun stop() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        _state.value = PlaybackState.IDLE
    }
}
