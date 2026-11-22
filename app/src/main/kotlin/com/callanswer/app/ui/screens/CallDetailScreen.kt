package com.callanswer.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.ScreenHeader
import com.callanswer.app.ui.components.SectionHeader
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.callanswer.app.R
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppDialog
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.BubbleSpeaker
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.components.TranscriptBubble
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.Speaker
import kotlinx.coroutines.launch

/** Whether the linked file can still be opened. */

private fun Speaker.bubble() = when (this) {
    Speaker.CALLER -> BubbleSpeaker.CALLER
    Speaker.AGENT -> BubbleSpeaker.AGENT
    Speaker.USER -> BubbleSpeaker.USER
    Speaker.LIVE -> BubbleSpeaker.LIVE
}

/**
 * Call detail with entry deletion (10.5). Recording playback comes from the cloud call log (task 12.10).
 */
@Composable
fun CallDetailScreen(entryId: String, resumeTick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { context.callAnswerApp().database.callLogDao() }
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf<CallLogEntry?>(null) }
    var reload by remember { mutableStateOf(0) }
    var confirmEntryDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(entryId, resumeTick, reload) {
        val e = dao.get(entryId)
        entry = e
        loaded = true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm),
    ) {
        ScreenHeader(stringResource(R.string.detail_title), onBack)
        val e = entry
        if (e == null) {
            if (!loaded) {
                Box(Modifier.fillMaxWidth().padding(AppDimens.spaceLg), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            if (loaded) StatusBanner(stringResource(R.string.detail_not_found), BannerSeverity.WARNING)
        } else {
            HeaderCard(e)
            error?.let { StatusBanner(stringResource(it), BannerSeverity.ERROR) }
            Section(stringResource(R.string.detail_gates))
            if (e.gateStatements.isEmpty()) Text(stringResource(R.string.detail_none))
            e.gateStatements.forEach { g ->
                Text(
                    stringResource(
                        R.string.detail_gate_line,
                        g.gateNumber,
                        stringResource(if (g.proactive) R.string.detail_gate_proactive else R.string.detail_gate_response),
                        stringResource(if (g.fictitious) R.string.detail_gate_fictitious else R.string.detail_gate_real),
                    ),
                )
            }
            Section(stringResource(R.string.detail_takeovers))
            if (e.takeovers.isEmpty()) Text(stringResource(R.string.detail_none))
            e.takeovers.forEach { t ->
                Text(
                    stringResource(
                        R.string.detail_takeover_line,
                        CallLogFormat.dateTime(t.startedAtEpochMs, t.zoneId),
                        t.durationSec,
                    ),
                )
            }
            Section(stringResource(R.string.detail_transcript))
            if (e.transcript.isEmpty()) Text(stringResource(R.string.detail_transcript_empty))
            e.transcript.forEach { u -> TranscriptBubble(u.speaker.bubble(), u.text) }
            AppButton(stringResource(R.string.calllog_report_spam), onClick = { startReportSpam(context, e.id) }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = { confirmEntryDelete = true },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = AppDimens.minTouchTarget),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.detail_delete_entry)) }
        }
    }

    val current = entry
    if (confirmEntryDelete && current != null) {
        AppDialog(
            title = stringResource(R.string.detail_delete_entry_title),
            onConfirm = {
                confirmEntryDelete = false
                scope.launch {
                    if (dao.deleteById(current.id) > 0) {
                        entry = null
                        onBack()
                    } else {
                        error = R.string.detail_delete_entry_failed
                    }
                }
            },
            onDismiss = { confirmEntryDelete = false },
        ) { Text(stringResource(R.string.detail_delete_entry_body)) }
    }
}

@Composable
private fun Section(title: String) = SectionHeader(title)

/** Prominent caller number, time and outcome chips, then the remaining fields in one card. */
@Composable
private fun HeaderCard(e: CallLogEntry) {
    val number = e.callerNumber ?: stringResource(R.string.calllog_unknown_number)
    val whenText = CallLogFormat.dateTime(e.startedAtEpochMs, e.zoneId)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(AppDimens.spaceLg), verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            Text(number, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(whenText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
                AssistChip(onClick = {}, label = { Text(CallLogFormat.label(e.outcome)) })
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.calllog_report_chip, reportStatusLabel(e.report.status))) })
            }
        }
    }
    AppCard(contentDescription = stringResource(R.string.detail_title)) { EntryFields(e) }
}

@Composable
private fun EntryFields(e: CallLogEntry) {
    Text(stringResource(R.string.detail_number, e.callerNumber ?: stringResource(R.string.calllog_unknown_number)))
    Text(stringResource(R.string.detail_started, CallLogFormat.dateTime(e.startedAtEpochMs, e.zoneId)))
    Text(stringResource(R.string.detail_duration, e.durationSec))
    Text(stringResource(R.string.detail_language, e.language?.name ?: stringResource(R.string.detail_language_unknown)))
    Text(stringResource(R.string.detail_intent, CallLogFormat.label(e.intent)))
    Text(stringResource(R.string.detail_outcome, CallLogFormat.label(e.outcome)))
    Text(stringResource(R.string.detail_end_reason, CallLogFormat.label(e.endReason)))
    Text(stringResource(R.string.detail_voice, CallLogFormat.label(e.voiceGender)))
    e.voiceSubstitutions.forEach { Text(stringResource(R.string.detail_voice_substitution, it.language.name, it.usedVoiceId)) }
    e.stallingDurationSec?.let { Text(stringResource(R.string.detail_stalling, it)) }
    e.interruption?.let { Text(stringResource(R.string.detail_interruption, it.startedAtMs, it.durationMs)) }
    e.transfer?.let {
        Text(stringResource(R.string.detail_transfer, CallLogFormat.label(it.path), CallLogFormat.label(it.outcome)))
    }
    if (e.notes.isNotEmpty()) {
        Text(stringResource(R.string.detail_notes, e.notes.joinToString { CallLogFormat.label(it) }))
    }
    Text(stringResource(R.string.detail_report, reportStatusLabel(e.report.status)))
    e.report.reportedAtEpochMs?.let {
        Text(stringResource(R.string.detail_reported_at, CallLogFormat.dateTime(it, e.report.reportedZoneId ?: e.zoneId)))
    }
}
