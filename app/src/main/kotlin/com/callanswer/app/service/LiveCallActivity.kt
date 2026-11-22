package com.callanswer.app.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.callanswer.app.R
import com.callanswer.app.audio.CallAudioAccessibilityService
import com.callanswer.app.translate.textFor
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppTextField
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.BubbleSpeaker
import com.callanswer.app.ui.components.ChipRow
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.components.TranscriptBubble
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.app.ui.theme.AppTheme
import com.callanswer.core.engine.Mode
import com.callanswer.core.model.IntentCategory
import com.callanswer.core.model.Language
import com.callanswer.core.model.QuickReply
import com.callanswer.core.model.Speaker

/**
 * Host for the Live_Call_Screen. Shows over the lock screen and is the full-screen intent target.
 * The screen renders [CallForegroundService.uiState] and sends control intents to the service (13.4).
 */
class LiveCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            AppTheme {
                val state by CallForegroundService.uiState.collectAsState()
                val assisted by CallAudioAccessibilityService.assistedMode.collectAsState()
                val ctx: Context = this
                LiveCallScreen(
                    state = state,
                    assistedMode = assisted,
                    onTakeOver = { send(ctx, CallForegroundService.ACTION_TAKE_OVER_UI) },
                    onMute = { send(ctx, CallForegroundService.ACTION_MUTE) },
                    onResume = { send(ctx, CallForegroundService.ACTION_RESUME) },
                    onEnd = { send(ctx, CallForegroundService.ACTION_END) },
                    onReply = { text ->
                        send(ctx, CallForegroundService.ACTION_QUICK_REPLY) {
                            putExtra(CallForegroundService.EXTRA_TEXT, text)
                        }
                    },
                )
            }
        }
    }

    private fun send(context: Context, action: String, extras: Intent.() -> Unit = {}) {
        runCatching {
            context.startService(
                Intent(context, CallForegroundService::class.java).setAction(action).apply(extras),
            )
        }
    }
}

private fun Speaker.toBubble(): BubbleSpeaker = when (this) {
    Speaker.CALLER -> BubbleSpeaker.CALLER
    Speaker.AGENT -> BubbleSpeaker.AGENT
    Speaker.USER -> BubbleSpeaker.USER
    Speaker.LIVE -> BubbleSpeaker.LIVE
}

@Composable
fun LiveCallScreen(
    state: LiveCallUiState,
    assistedMode: Boolean,
    onTakeOver: () -> Unit,
    onMute: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onReply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val unknownNumber = stringResource(R.string.call_notif_unknown_number)
    val intentLabel = stringResource(
        R.string.live_intent_label,
        stringResource(
            when (state.intent) {
                IntentCategory.DELIVERY -> R.string.live_intent_delivery
                IntentCategory.DOOR_NOT_OPENED -> R.string.live_intent_door
                IntentCategory.SOLICITATION -> R.string.live_intent_solicitation
                IntentCategory.UNKNOWN -> R.string.live_intent_unknown
            },
        ),
    )
    val languageLabel = stringResource(
        R.string.live_language_label,
        stringResource(
            when (state.language) {
                Language.HI -> R.string.live_language_hi
                Language.EN -> R.string.live_language_en
                null -> R.string.live_language_unknown
            },
        ),
    )
    val takeOver = state.mode == Mode.TAKE_OVER
    val agentActive = state.mode == Mode.AGENT_ACTIVE
    val listState = rememberLazyListState()
    LaunchedEffect(state.transcript.size) {
        if (state.transcript.isNotEmpty()) listState.animateScrollToItem(state.transcript.lastIndex)
    }
    var reply by remember { mutableStateOf("") }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(AppDimens.spaceMd).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm),
        ) {
            val stateText = when {
                !state.active -> stringResource(R.string.live_call_ended)
                takeOver -> stringResource(R.string.live_banner_take_over)
                state.mode == Mode.AGENT_MUTED -> stringResource(R.string.live_banner_muted)
                else -> null
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(AppDimens.spaceMd), verticalArrangement = Arrangement.spacedBy(AppDimens.spaceXs)) {
                    Text(
                        stringResource(R.string.live_title, state.callerNumber ?: unknownNumber),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    stateText?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            ChipRow(
                labels = listOf(intentLabel, languageLabel),
                selected = setOf(intentLabel, languageLabel),
                onChipClick = {},
            )
            DebugNetworkOverlay()
            if (assistedMode) StatusBanner(stringResource(R.string.live_banner_assisted), BannerSeverity.WARNING)

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm),
            ) {
                if (state.transcript.isEmpty()) {
                    item { Text(stringResource(R.string.live_transcript_empty)) }
                }
                items(state.transcript) { u -> TranscriptBubble(u.speaker.toBubble(), u.text) }
            }

            // Quick_Replies are spoken by the Agent, so they apply only while the Agent is active (13.14).
            val replyLanguage = state.language ?: Language.EN
            val replies: Map<String, QuickReply> =
                state.quickReplies.associateBy { it.textFor(replyLanguage, null) }.filterKeys { it.isNotBlank() }
            if (replies.isNotEmpty() && agentActive && state.active) {
                ChipRow(
                    labels = replies.keys.toList(),
                    selected = emptySet(),
                    onChipClick = { onReply(it) },
                )
            }
            AppTextField(
                value = reply,
                onValueChange = { reply = it },
                label = stringResource(R.string.live_quick_reply_field),
                enabled = agentActive && state.active,
            )
            AppButton(
                text = stringResource(R.string.live_quick_reply_send),
                onClick = {
                    val text = reply.trim()
                    if (text.isNotEmpty()) {
                        onReply(text)
                        reply = ""
                    }
                },
                enabled = agentActive && state.active && reply.isNotBlank(),
                primary = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm), modifier = Modifier.fillMaxWidth()) {
                AppButton(
                    text = stringResource(R.string.call_action_take_over),
                    onClick = onTakeOver,
                    enabled = state.active && !takeOver,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = AppDimens.largeControl),
                )
                AppButton(
                    text = stringResource(R.string.call_action_mute),
                    onClick = onMute,
                    enabled = state.active && agentActive,
                    primary = false,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = AppDimens.largeControl),
                )
                AppButton(
                    text = stringResource(R.string.call_action_resume),
                    onClick = onResume,
                    enabled = state.active && !agentActive,
                    primary = false,
                    modifier = Modifier.weight(1f).defaultMinSize(minHeight = AppDimens.largeControl),
                )
            }
            val endLabel = stringResource(R.string.call_action_end)
            Button(
                onClick = onEnd,
                enabled = state.active,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.largeControl)
                    .semantics { contentDescription = endLabel },
            ) { Text(endLabel, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Preview
@Composable
private fun LiveCallScreenPreview() = AppTheme {
    LiveCallScreen(
        state = LiveCallUiState(
            active = true,
            callerNumber = "+911234567890",
            mode = Mode.TAKE_OVER,
            quickReplies = listOf(QuickReply("Please wait", "कृपया प्रतीक्षा करें")),
        ),
        assistedMode = true,
        onTakeOver = {}, onMute = {}, onResume = {}, onEnd = {}, onReply = {},
    )
}
