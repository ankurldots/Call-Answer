package com.callanswer.app.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.data.SettingsRepository
import com.callanswer.app.data.SyncResult
import com.callanswer.core.model.Settings
import kotlinx.coroutines.CancellationException

/**
 * What the settings screens show: the cached server document with the pending edit applied (the server is the source
 * of truth, the cache is what works offline), and the result of the last sync.
 */
class CloudSettingsState(val repo: SettingsRepository) {
    var settings by mutableStateOf<Settings?>(null)
        private set
    /** True after the first sync attempt finished (it may have failed offline). */
    var loaded by mutableStateOf(false)
        private set
    /** A local edit has not reached the server yet. */
    var pending by mutableStateOf(false)
        private set
    /** Fields changed on both sides wait for the User's choice. */
    var needsChoice by mutableStateOf(false)
        private set

    private suspend fun publish() {
        settings = settingsFromJson(repo.effective())
        pending = repo.pendingEdit() != null
    }

    /** Shows the cache, then reads the server document, sends a pending edit when there is one, and shows the result. */
    suspend fun refresh() {
        try {
            publish()
            needsChoice = repo.sync() is SyncResult.NeedsResolution
            publish()
        } catch (e: CancellationException) {
            throw e
        } finally {
            loaded = true
        }
    }

    /** Stores the fields of [new] that differ from what is shown, then sends them. */
    suspend fun save(new: Settings): EditOutcome {
        val changed = changedFields(settings ?: Settings(), new)
        if (changed.isEmpty()) return EditOutcome.Applied
        repo.saveEdit(changed)
        val result = repo.sync()
        // The server rejected a field: drop the edit so the saved value stays (Requirement 20 criterion 3).
        if (result is SyncResult.Invalid) repo.discardPending()
        needsChoice = result is SyncResult.NeedsResolution
        publish()
        return result.toOutcome()
    }

    /** Toggle-style change; false when the server rejected it. */
    suspend fun change(f: (Settings) -> Settings): Boolean = save(f(settings ?: Settings())) !is EditOutcome.Rejected
}

/** Increments each time the device gets a network, so a pending edit is sent on reconnect. */
@Composable
private fun rememberReconnectTick(): Int {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { tick++ }
        }
        val registered = runCatching { cm?.registerDefaultNetworkCallback(callback) }.isSuccess
        onDispose { if (registered) runCatching { cm?.unregisterNetworkCallback(callback) } }
    }
    return tick
}

/** One [CloudSettingsState] per screen, refreshed on entry and on every network that becomes available. */
@Composable
fun rememberCloudSettings(): CloudSettingsState {
    val repo = (LocalContext.current.applicationContext as CallAnswerApp).settingsRepository
    val state = remember(repo) { CloudSettingsState(repo) }
    val tick = rememberReconnectTick()
    LaunchedEffect(state, tick) { state.refresh() }
    return state
}
