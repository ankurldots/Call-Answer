package com.callanswer.app.report

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.R
import com.callanswer.app.service.NotificationCenter
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppDialog
import com.callanswer.app.ui.components.AppTextField
import com.callanswer.app.ui.theme.AppTheme
import com.callanswer.core.model.ReportStatus
import com.callanswer.core.report.DescriptionError
import kotlinx.coroutines.launch

/**
 * Dialog-themed host of the spam report flow (Requirement 14). Opened by the post-call notification action
 * (`PendingIntent.getActivity`, no trampoline) and from the Call_Log. Extra [NotificationCenter.EXTRA_ENTRY_ID].
 */
class ReportSpamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent.getStringExtra(NotificationCenter.EXTRA_ENTRY_ID)
        if (entryId.isNullOrBlank()) {
            finish()
            return
        }
        val app = application as CallAnswerApp
        setContent {
            AppTheme {
                ReportSpamFlow(app.spamReporter, entryId, app, onFinish = { finish() })
            }
        }
    }
}

@Composable
private fun ReportSpamFlow(reporter: SpamReporter, entryId: String, app: CallAnswerApp, onFinish: () -> Unit) {
    var state by remember { mutableStateOf<ReportUiState?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(entryId) { state = reporter.onReportSpam(entryId) }

    fun run(block: suspend () -> ReportUiState) {
        scope.launch { state = block() }
    }

    when (val s = state) {
        null -> Unit
        ReportUiState.Idle -> LaunchedEffect(Unit) { onFinish() }
        is ReportUiState.Confirm -> ConfirmDialog(
            s,
            onEdit = { text -> run { reporter.updateDescription(s.entryId, s.attemptId, text) } },
            onConfirm = { run { reporter.confirm(s.entryId, s.attemptId) } },
            onCancel = {
                app.appScope.launch { reporter.cancel(s.entryId, s.attemptId) }
                onFinish()
            },
        )
        is ReportUiState.AskRepeat -> AppDialog(
            title = stringResource(R.string.report_repeat_title),
            onConfirm = { run { reporter.onReportSpam(entryId, repeatConfirmed = true) } },
            onDismiss = onFinish,
        ) {
            Text(stringResource(R.string.report_repeat_body, statusLabel(s.status)))
        }
        ReportUiState.CannotReport -> InfoDialog(stringResource(R.string.report_cannot_report), onFinish)
        ReportUiState.NoMessagingApp -> InfoDialog(stringResource(R.string.report_no_messaging_app), onFinish)
        ReportUiState.HandedToMessagingApp -> LaunchedEffect(Unit) { onFinish() }
        is ReportUiState.SilentSent -> InfoDialog(stringResource(R.string.report_silent_sent, s.text), onFinish)
        ReportUiState.OfferRetry -> AppDialog(
            title = stringResource(R.string.report_title),
            onConfirm = { run { reporter.onReportSpam(entryId) } },
            onDismiss = onFinish,
            confirmText = stringResource(R.string.report_retry),
        ) {
            Text(stringResource(R.string.report_retry_offer))
        }
    }
}

@Composable
private fun ConfirmDialog(
    s: ReportUiState.Confirm,
    onEdit: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember(s.attemptId) { mutableStateOf(s.description) }
    AppDialog(title = stringResource(R.string.report_title), onConfirm = {
        if (s.validationError == null) onConfirm()
    }, onDismiss = onCancel) {
        Column {
            Text(stringResource(R.string.report_message_label), style = MaterialTheme.typography.labelMedium)
            Text(s.text)
            if (s.truncated) Text(stringResource(R.string.report_truncated_note))
            Spacer(Modifier.height(12.dp))
            AppTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    onEdit(it)
                },
                label = stringResource(R.string.report_description_label),
                errorMessage = s.validationError?.let { errorText(it) },
            )
            if (s.permissionNote) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.report_permission_note))
            }
        }
    }
}

@Composable
private fun InfoDialog(message: String, onFinish: () -> Unit) {
    AppDialog(
        title = stringResource(R.string.report_title),
        onConfirm = onFinish,
        onDismiss = onFinish,
        confirmText = stringResource(R.string.report_ok),
    ) { Text(message) }
}

@Composable
private fun errorText(e: DescriptionError): String = stringResource(
    when (e) {
        DescriptionError.EMPTY -> R.string.report_error_empty
        DescriptionError.NON_ASCII_OR_CONTROL -> R.string.report_error_ascii
        DescriptionError.CONTAINS_COMMA -> R.string.report_error_comma
        DescriptionError.CONTAINS_PROTECTED_VALUE -> R.string.report_error_protected
    },
)

@Composable
private fun statusLabel(status: ReportStatus): String = stringResource(
    when (status) {
        ReportStatus.PREPARED -> R.string.report_status_prepared
        ReportStatus.HANDED_TO_MESSAGING_APP -> R.string.report_status_handed_off
        else -> R.string.report_status_sent
    },
)
