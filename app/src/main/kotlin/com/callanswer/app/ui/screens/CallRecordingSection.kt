package com.callanswer.app.ui.screens

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.callanswer.app.R
import com.callanswer.app.api.ApiException
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.SectionHeader
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import kotlinx.coroutines.launch

private enum class PlayState { IDLE, LOADING, PLAYING }

/**
 * Recording playback (Requirement 18 criterion 7). Each Play press asks the server for a fresh presigned https URL
 * (valid under 15 minutes) and streams it with the platform [MediaPlayer]; the URL is never stored or logged.
 */
@Composable
internal fun RecordingSection(callId: String, hasRecording: Boolean) {
    val context = LocalContext.current
    val api = remember { context.callAnswerApp().companionApi }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PlayState.IDLE) }
    var failed by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    val player = remember { arrayOfNulls<MediaPlayer>(1) }

    fun stop() {
        player[0]?.let { runCatching { it.release() } }
        player[0] = null
        state = PlayState.IDLE
    }
    DisposableEffect(callId) { onDispose { stop() } }

    SectionHeader(stringResource(R.string.recording_title))
    if (!hasRecording || missing) {
        Text(stringResource(R.string.recording_none))
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
        if (failed) StatusBanner(stringResource(R.string.recording_failed), BannerSeverity.ERROR)
        if (state == PlayState.LOADING) Text(stringResource(R.string.recording_loading))
        AppButton(
            stringResource(if (state == PlayState.PLAYING) R.string.recording_stop else R.string.recording_play),
            onClick = {
                if (state != PlayState.IDLE) {
                    stop()
                } else {
                    failed = false
                    state = PlayState.LOADING
                    scope.launch {
                        try {
                            val url = api.recordingUrl(callId).url
                            val mp = MediaPlayer()
                            player[0] = mp
                            mp.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .build(),
                            )
                            mp.setOnPreparedListener { it.start(); state = PlayState.PLAYING }
                            mp.setOnCompletionListener { stop() }
                            mp.setOnErrorListener { _, _, _ -> stop(); failed = true; true }
                            mp.setDataSource(url)
                            mp.prepareAsync()
                        } catch (e: ApiException.NotFound) {
                            stop(); missing = true
                        } catch (e: ApiException) {
                            stop(); failed = true
                        } catch (e: java.io.IOException) {
                            stop(); failed = true
                        } catch (e: IllegalStateException) {
                            stop(); failed = true
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
