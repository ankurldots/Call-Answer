package com.callanswer.app.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.callanswer.core.model.DEFAULT_AGENT_NAME
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

/** The agent name the User chose in settings; blank falls back to the default. */
fun displayAgentName(raw: String): String = raw.trim().ifEmpty { DEFAULT_AGENT_NAME }

/** Reads the name for non-Compose callers (notifications). DataStore serves it from memory after the first read. */
fun currentAgentName(context: Context): String =
    runCatching { runBlocking { displayAgentName(SettingsStore.create(context).current().agentName) } }
        .getOrDefault(DEFAULT_AGENT_NAME)

/** The agent name as state, updating when the User changes it in settings. */
@Composable
fun rememberAgentName(): String {
    val context = LocalContext.current
    val flow = remember(context) { SettingsStore.create(context).settings.map { displayAgentName(it.agentName) } }
    val name by flow.collectAsState(initial = DEFAULT_AGENT_NAME)
    return name
}
