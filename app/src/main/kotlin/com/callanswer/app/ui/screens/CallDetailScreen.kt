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
import com.callanswer.app.recording.FolderResult
import com.callanswer.app.recording.PlaybackController
import com.callanswer.app.recording.PlaybackState
import com.callanswer.app.recording.RecordingFolderRepository
import com.callanswer.app.recording.RecordingLinker
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppDialog
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.BubbleSpeaker
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.components.TranscriptBubble
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.RecordingStatus
import com.callanswer.core.model.Speaker
import kotlinx.coroutines.launch

/** Whether the linked file can still be opened. */
private enum class FileState { UNKNOWN, PRESENT, GONE, ACCESS_LOST }

private fun Speaker.bubble() = when (this) {
    Speaker.CALLER -> BubbleSpeaker.CALLER
    Speaker.AGENT -> BubbleSpeaker.AGENT
    Speaker.USER -> BubbleSpeaker.USER
    Speaker.LIVE -> BubbleSpeaker.LIVE
}

/**
 * Call detail with player controls (12.8) and entry deletion (10.5). Deleting the entry is followed by
 * "Also delete the recording?" whose default answer is no (10.5, 12.9).
 */
@Composable
fun CallDetailScreen(entryId: String, resumeTick: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { context.callAnswerApp().database.callLogDao() }
    val repository = remember { RecordingFolderRepository(context) }
    val player = remember { PlaybackController(context) }
    DisposableEffect(player) { onDispose { player.stop() } }
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf<CallLogEntry?>(null) }
    var fileState by remember { mutableStateOf(FileState.UNKNOWN) }
    var reload by remember { mutableStateOf(0) }
    var confirmEntryDelete by remember { mutableStateOf(false) }
    var confirmRecordingDelete by remember { mutableStateOf(false) }
    var askAlsoDelete by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(entryId, resumeTick, reload) {
        val e = dao.get(entryId)
        entry = e
        loaded = true
        fileState = FileState.UNKNOWN
        val uri = e?.recording?.uri
        if (e != null && e.recording.status == RecordingStatus.LINKED && uri != null) {
            fileState = when (val r = repository.exists(Uri.parse(uri))) {
                is FolderResult.Ok -> if (r.value) FileState.PRESENT else FileState.GONE
                FolderResult.AccessLost -> FileState.ACCESS_LOST
            }
        }
    }
    val playbackState by player.state.collectAsState()

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
            if (loaded && askAlsoDelete == null) StatusBanner(stringResource(R.string.detail_not_found), BannerSeverity.WARNING)
        } else {
            HeaderCard(e)
            Section(stringResource(R.string.detail_recording))
            AppCard(contentDescription = stringResource(R.string.detail_recording)) {
            RecordingSection(
                e, fileState, playbackState,
                onPlay = { e.recording.uri?.let { player.play(Uri.parse(it)) } },
                onPause = player::pause,
                onResume = player::resume,
                onStop = player::stop,
                onSearchAgain = {
                    RecordingLinker.searchAgain(context, e.id)
                },
                onRefresh = { reload++ },
                onDeleteRecording = { confirmRecordingDelete = true },
            )
            }
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
                    player.stop()
                    if (dao.deleteById(current.id) > 0) {
                        val uri = current.recording.uri
                        entry = null
                        if (current.recording.status == RecordingStatus.LINKED && uri != null) askAlsoDelete = uri else onBack()
                    } else {
                        error = R.string.detail_delete_entry_failed
                    }
                }
            },
            onDismiss = { confirmEntryDelete = false },
        ) { Text(stringResource(R.string.detail_delete_entry_body)) }
    }
    askAlsoDelete?.let { uri ->
        // Dismissing (No, back, or tapping outside) keeps the file: the default answer is no.
        AppDialog(
            title = stringResource(R.string.detail_also_delete_title),
            confirmText = stringResource(R.string.detail_yes),
            dismissText = stringResource(R.string.detail_no),
            onConfirm = {
                askAlsoDelete = null
                scope.launch {
                    repository.delete(Uri.parse(uri))
                    onBack()
                }
            },
            onDismiss = {
                askAlsoDelete = null
                onBack()
            },
        ) { Text(stringResource(R.string.detail_also_delete_body)) }
    }
    if (confirmRecordingDelete && current != null) {
        AppDialog(
            title = stringResource(R.string.detail_delete_recording_title),
            onConfirm = {
                confirmRecordingDelete = false
                val uri = current.recording.uri
                if (uri != null) scope.launch {
                    player.stop()
                    val r = repository.delete(Uri.parse(uri))
                    if (r is FolderResult.Ok && r.value) {
                        dao.updateRecordingStatus(current.id, RecordingStatus.DELETED)
                        error = null
                        reload++
                    } else {
                        error = R.string.detail_delete_recording_failed
                    }
                }
            },
            onDismiss = { confirmRecordingDelete = false },
        ) { Text(stringResource(R.string.detail_delete_recording_body)) }
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

@Composable
private fun RecordingSection(
    e: CallLogEntry,
    fileState: FileState,
    playback: PlaybackState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSearchAgain: () -> Unit,
    onRefresh: () -> Unit,
    onDeleteRecording: () -> Unit,
) {
    val status = e.recording.status
    when (status) {
        RecordingStatus.LINKED -> {
            // 12.10: a file removed outside the app, or a playback error, shows "recording unavailable".
            val unavailable = fileState == FileState.GONE || playback == PlaybackState.ERROR
            if (unavailable) {
                StatusBanner(stringResource(R.string.detail_recording_unavailable), BannerSeverity.WARNING)
            } else if (fileState == FileState.ACCESS_LOST) {
                StatusBanner(stringResource(R.string.detail_recording_access_lost), BannerSeverity.WARNING)
            } else {
                val length = e.recording.durationMs?.let { "${it / 1000} s" } ?: stringResource(R.string.detail_duration_unknown)
                val size = e.recording.sizeBytes?.let { "${it / 1024} KB" } ?: stringResource(R.string.detail_size_unknown)
                Text(stringResource(R.string.detail_recording_linked, length, size))
                PlayerControls(playback, onPlay, onPause, onResume, onStop)
            }
            if (!unavailable && fileState != FileState.ACCESS_LOST) {
                AppButton(stringResource(R.string.detail_delete_recording), onClick = onDeleteRecording, primary = false)
            }
            if (unavailable) AppButton(stringResource(R.string.detail_search_again), onClick = onSearchAgain, primary = false)
        }
        RecordingStatus.PENDING -> Text(stringResource(R.string.detail_recording_pending))
        RecordingStatus.DELETED -> Text(stringResource(R.string.detail_recording_deleted))
        RecordingStatus.NOT_FOUND, RecordingStatus.NO_FOLDER_SELECTED, RecordingStatus.FOLDER_ACCESS_LOST -> {
            Text(
                stringResource(
                    when (status) {
                        RecordingStatus.NOT_FOUND -> R.string.detail_recording_not_found
                        RecordingStatus.NO_FOLDER_SELECTED -> R.string.detail_recording_no_folder
                        else -> R.string.detail_recording_access_lost
                    },
                ),
            )
            AppButton(stringResource(R.string.detail_search_again), onClick = { onSearchAgain(); onRefresh() }, primary = false)
        }
    }
}

/** Play, pause, resume and stop (12.8). */
@Composable
private fun PlayerControls(
    state: PlaybackState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
        when (state) {
            PlaybackState.PLAYING -> AppButton(stringResource(R.string.player_pause), onClick = onPause)
            PlaybackState.PAUSED, PlaybackState.ENDED -> AppButton(stringResource(R.string.player_resume), onClick = onResume)
            else -> AppButton(stringResource(R.string.player_play), onClick = onPlay)
        }
        AppButton(
            stringResource(R.string.player_stop),
            onClick = onStop,
            enabled = state != PlaybackState.IDLE,
            primary = false,
        )
    }
}
