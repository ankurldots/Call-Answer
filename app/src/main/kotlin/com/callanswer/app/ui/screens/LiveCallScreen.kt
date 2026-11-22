package com.callanswer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.callanswer.app.R
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.api.LiveSocket
import com.callanswer.app.api.endCall
import com.callanswer.app.api.sendQuickReply
import com.callanswer.app.api.takeOver
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppDialog
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.ScreenHeader
import com.callanswer.app.ui.components.SectionHeader
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.components.TranscriptBubble
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.model.CoreJson
import com.callanswer.core.model.QuickReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer

object LiveCallTags {
    const val TAKE_OVER = "live_take_over"
    const val END_CALL = "live_end_call"
    const val ENDED = "live_ended"
}

/** Pause before the live feed reconnects after a failure. */
private const val RECONNECT_DELAY_MS = 2_000L

/**
 * Live call screen (Requirement 19). It follows `/live` for one call, reconnecting after a failure, and sends the
 * take over, end, and quick reply actions. A call that is already in the call log is shown as ended.
 */
@Composable
fun LiveCallScreen(callId: String, onBack: () -> Unit) {
    val app = LocalContext.current.callAnswerApp()
    val api = app.companionApi
    var state by remember(callId) { mutableStateOf(LiveCallState(callId)) }
    var reconnecting by remember(callId) { mutableStateOf(false) }
    var quickReplies by remember { mutableStateOf<List<QuickReply>>(emptyList()) }

    LaunchedEffect(callId) {
        quickReplies = runCatching {
            val arr = app.settingsRepository.effective()?.get("quickReplies") ?: return@runCatching emptyList()
            CoreJson.decodeFromJsonElement(ListSerializer(QuickReply.serializer()), arr)
        }.getOrDefault(emptyList())
    }
    // A finished call has its entry in the call log: show it as ended (Requirement 19 criterion 9).
    LaunchedEffect(callId) {
        val entry = try { api.getCall(callId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (entry != null && !state.ended) {
            state = state.copy(
                ended = true,
                callerNumber = state.callerNumber ?: entry.callerNumber,
                startedAtMs = state.startedAtMs ?: entry.startedAtEpochMs,
                endedAtMs = state.startedAtMs?.let { it + entry.durationSec * 1000 },
            )
        }
    }
    LaunchedEffect(callId) {
        followLive(app.liveSocket, callId, { reconnecting = it }) { ev -> state = state.reduce(ev, System.currentTimeMillis()) }
    }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.ended) {
        while (!state.ended) { now = System.currentTimeMillis(); delay(1_000) }
    }

    LiveCallContent(
        state = state,
        nowMs = now,
        reconnecting = reconnecting,
        quickReplies = quickReplies,
        api = api,
        onBridge = { state = state.copy(bridge = it) },
        onBack = onBack,
    )
}

/** Collects `/live` for [callId] and reconnects until the call ends or the collector is cancelled. */
internal suspend fun followLive(socket: LiveSocket, callId: String, onReconnecting: (Boolean) -> Unit, onEvent: (com.callanswer.app.api.LiveEvent) -> Unit) {
    var ended = false
    while (!ended) {
        try {
            // The feed stops at call_ended even if the server keeps the socket open (closing the flow closes the socket).
            socket.events().transformWhile { ev -> emit(ev); ev.type != "call_ended" || ev.data.isForOther(callId) }.collect { ev ->
                onReconnecting(false)
                if (ev.type == "call_ended" && !ev.data.isForOther(callId)) ended = true
                onEvent(ev)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException.Unauthorized) {
            return
        } catch (e: ApiException) {
            // fall through to the reconnect
        }
        if (!ended) {
            onReconnecting(true)
            delay(RECONNECT_DELAY_MS)
        }
    }
}

@Composable
internal fun LiveCallContent(
    state: LiveCallState,
    nowMs: Long,
    reconnecting: Boolean,
    quickReplies: List<QuickReply>,
    api: CompanionApi,
    onBridge: (BridgeStatus) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.livecall_unknown)
    val listState = rememberLazyListState()
    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(AppDimens.spaceMd), verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
        ScreenHeader(stringResource(R.string.livecall_title), onBack)
        val numberLine = stringResource(R.string.livecall_number, state.callerNumber ?: unknown)
        val intentLine = stringResource(R.string.livecall_intent, state.intent?.let(::humanize) ?: unknown)
        val languageLine = stringResource(R.string.livecall_language, state.language?.let(::humanize) ?: unknown)
        val elapsedLine = stringResource(R.string.livecall_elapsed, formatElapsed(state.elapsedSec(nowMs)))
        AppCard(contentDescription = listOf(numberLine, intentLine, languageLine, elapsedLine).joinToString(". ")) {
            Text(numberLine, style = MaterialTheme.typography.titleMedium)
            Text(intentLine)
            Text(languageLine)
            Text(elapsedLine)
        }
        if (state.ended) StatusBanner(stringResource(R.string.livecall_ended), BannerSeverity.WARNING, Modifier.testTag(LiveCallTags.ENDED))
        else if (reconnecting) StatusBanner(stringResource(R.string.livecall_reconnecting), BannerSeverity.INFO)
        when (state.bridge) {
            BridgeStatus.CONNECTING -> StatusBanner(stringResource(R.string.livecall_bridge_connecting))
            BridgeStatus.CONNECTED -> StatusBanner(stringResource(R.string.livecall_bridge_connected))
            BridgeStatus.FAILED -> StatusBanner(stringResource(R.string.livecall_bridge_failed), BannerSeverity.ERROR)
            BridgeStatus.NONE -> Unit
        }
        if (failed) StatusBanner(stringResource(R.string.livecall_action_failed), BannerSeverity.ERROR)

        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm), modifier = Modifier.fillMaxWidth()) {
            AppButton(
                stringResource(R.string.livecall_take_over),
                onClick = {
                    failed = false
                    onBridge(BridgeStatus.CONNECTING)
                    scope.launch {
                        try { api.takeOver(state.callId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            onBridge(BridgeStatus.FAILED)
                        }
                    }
                },
                enabled = state.canTakeOver,
                modifier = Modifier.weight(1f).testTag(LiveCallTags.TAKE_OVER),
            )
            AppButton(
                stringResource(R.string.livecall_end_call),
                onClick = { confirmEnd = true },
                enabled = state.canEnd,
                primary = false,
                modifier = Modifier.weight(1f).testTag(LiveCallTags.END_CALL),
            )
        }
        if (quickReplies.isNotEmpty()) {
            SectionHeader(stringResource(R.string.livecall_quick_replies))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
                quickReplies.forEach { q ->
                    val text = state.quickReplyText(q.textEn, q.textHi)
                    AssistChip(
                        enabled = state.canQuickReply,
                        onClick = {
                            failed = false
                            scope.launch {
                                try { api.sendQuickReply(state.callId, text) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
                            }
                        },
                        label = { Text(text, maxLines = 1) },
                    )
                }
            }
        }
        SectionHeader(stringResource(R.string.livecall_transcript))
        if (state.transcript.isEmpty()) Text(stringResource(R.string.livecall_transcript_empty))
        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            items(state.transcript) { TranscriptBubble(it.speaker, it.text) }
        }
    }

    if (confirmEnd) {
        AppDialog(
            title = stringResource(R.string.livecall_end_confirm_title),
            onConfirm = {
                confirmEnd = false
                failed = false
                scope.launch {
                    try { api.endCall(state.callId) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true }
                }
            },
            onDismiss = { confirmEnd = false },
        ) { Text(stringResource(R.string.livecall_end_confirm_body)) }
    }
}

private fun kotlinx.serialization.json.JsonObject.isForOther(callId: String): Boolean {
    val id = listOf("callId", "call_id", "id").firstNotNullOfOrNull { (this[it] as? kotlinx.serialization.json.JsonPrimitive)?.content }
    return id != null && id != callId
}

private fun humanize(s: String) = s.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
