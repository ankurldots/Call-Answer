package com.callanswer.app.service

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.callanswer.app.BuildConfig
import com.callanswer.core.model.NetworkQuality
import com.callanswer.core.network.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Debug-only values for the patchy-network matrix (task 34.1). Writes are ignored in release builds. */
data class DebugNetworkInfo(
    val quality: NetworkQuality? = null,
    val replySource: Source? = null,
    val responseLatencyMs: Long? = null,
)

object DebugNetworkOverlayState {
    private val _info = MutableStateFlow(DebugNetworkInfo())
    val info: StateFlow<DebugNetworkInfo> = _info.asStateFlow()

    fun reportQuality(quality: NetworkQuality) {
        if (BuildConfig.DEBUG) _info.update { it.copy(quality = quality) }
    }

    /** Call when an Agent reply is chosen: [source] from the cloud/local router, [latencyMs] from caller end-of-speech to reply start. */
    fun reportReply(source: Source, latencyMs: Long) {
        if (BuildConfig.DEBUG) _info.update { it.copy(replySource = source, responseLatencyMs = latencyMs) }
    }

    fun reset() {
        _info.value = DebugNetworkInfo()
    }
}

/** One text line on the Live_Call_Screen. Renders nothing in release builds. */
@Composable
fun DebugNetworkOverlay(modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return
    val info by DebugNetworkOverlayState.info.collectAsState()
    Text(
        text = "DEBUG net=${info.quality ?: "-"} src=${info.replySource ?: "-"} latency=${info.responseLatencyMs?.let { "${it} ms" } ?: "-"}",
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.fillMaxWidth(),
    )
}
