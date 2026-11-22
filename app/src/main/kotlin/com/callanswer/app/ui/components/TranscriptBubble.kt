package com.callanswer.app.ui.components

import com.callanswer.app.data.rememberAgentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.callanswer.app.R
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.app.ui.theme.AppTheme

/** Speaker label shown on a bubble. Mirrors the core Speaker enum (CALLER, AGENT, USER, LIVE). */
enum class BubbleSpeaker { CALLER, AGENT, USER, LIVE }

@Composable
fun TranscriptBubble(speaker: BubbleSpeaker, text: String, modifier: Modifier = Modifier) {
    val agentName = rememberAgentName()
    val label = when (speaker) {
        BubbleSpeaker.CALLER -> stringResource(R.string.speaker_caller)
        BubbleSpeaker.AGENT -> agentName
        BubbleSpeaker.USER -> stringResource(R.string.speaker_user)
        BubbleSpeaker.LIVE -> stringResource(R.string.speaker_live)
    }
    val description = stringResource(R.string.cd_transcript_bubble, label, text)
    val alignEnd = speaker == BubbleSpeaker.AGENT || speaker == BubbleSpeaker.USER
    val container = when (speaker) {
        BubbleSpeaker.CALLER -> MaterialTheme.colorScheme.surfaceVariant
        BubbleSpeaker.AGENT -> MaterialTheme.colorScheme.primaryContainer
        BubbleSpeaker.USER -> MaterialTheme.colorScheme.secondaryContainer
        BubbleSpeaker.LIVE -> MaterialTheme.colorScheme.tertiaryContainer
    }
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = container,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.widthIn(max = AppDimens.bubbleMaxWidth),
        ) {
            Column(Modifier.padding(AppDimens.spaceSm)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview
@Composable
private fun TranscriptBubblePreview() = AppTheme {
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
        BubbleSpeaker.entries.forEach { TranscriptBubble(it, "Hello, I have a delivery.") }
    }
}
