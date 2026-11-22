package com.callanswer.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.R
import com.callanswer.app.report.ReportSpamActivity
import com.callanswer.app.service.NotificationCenter
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.ReportStatus

internal fun Context.callAnswerApp(): CallAnswerApp = applicationContext as CallAnswerApp

/** Opens the spam report flow of task 31.3 for one entry. */
internal fun startReportSpam(context: Context, entryId: String) {
    context.startActivity(
        Intent(context, ReportSpamActivity::class.java).putExtra(NotificationCenter.EXTRA_ENTRY_ID, entryId),
    )
}

@Composable
internal fun reportStatusLabel(status: ReportStatus): String = stringResource(
    when (status) {
        ReportStatus.NOT_REPORTED -> R.string.calllog_report_not_reported
        ReportStatus.PREPARED -> R.string.calllog_report_prepared
        ReportStatus.HANDED_TO_MESSAGING_APP -> R.string.calllog_report_handed
        ReportStatus.SENT -> R.string.calllog_report_sent
        ReportStatus.FAILED -> R.string.calllog_report_failed
        ReportStatus.CANNOT_REPORT_NUMBER_UNAVAILABLE -> R.string.calllog_report_cannot
    },
)

/**
 * Call_Log list, newest first (10.4). Every row has the report status chip and "Report spam";
 * FAILED rows also have "Retry report" (14.1). [resumeTick] reloads the list after returning from the report flow.
 */
@Composable
fun CallLogScreen(resumeTick: Int, onOpenEntry: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { context.callAnswerApp().database.callLogDao() }
    var entries by remember { mutableStateOf<List<CallLogEntry>?>(null) }
    LaunchedEffect(resumeTick) { entries = CallLogFormat.newestFirst(dao.list()) }

    Column(Modifier.fillMaxSize().padding(AppDimens.spaceMd), verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd)) {
        AppButton(stringResource(R.string.settings_back), onClick = onBack, primary = false)
        Text(
            stringResource(R.string.calllog_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        val list = entries
        if (list != null && list.isEmpty()) Text(stringResource(R.string.calllog_empty))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            items(list.orEmpty(), key = { it.id }) { entry ->
                CallLogRow(entry, onOpen = { onOpenEntry(entry.id) }, onReport = { startReportSpam(context, entry.id) })
            }
        }
    }
}

@Composable
private fun CallLogRow(entry: CallLogEntry, onOpen: () -> Unit, onReport: () -> Unit) {
    val number = entry.callerNumber ?: stringResource(R.string.calllog_unknown_number)
    val whenText = CallLogFormat.dateTime(entry.startedAtEpochMs, entry.zoneId)
    val status = reportStatusLabel(entry.report.status)
    AppCard(
        contentDescription = stringResource(R.string.calllog_row_description, number, whenText, CallLogFormat.label(entry.outcome)),
    ) {
        Text(number, style = MaterialTheme.typography.titleMedium)
        Text(whenText, style = MaterialTheme.typography.bodyMedium)
        Text(CallLogFormat.label(entry.outcome), style = MaterialTheme.typography.bodyMedium)
        AssistChip(onClick = {}, label = { Text(stringResource(R.string.calllog_report_chip, status)) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            AppButton(stringResource(R.string.calllog_details), onClick = onOpen, primary = false)
            AppButton(stringResource(R.string.calllog_report_spam), onClick = onReport)
            if (CallLogFormat.showRetry(entry.report.status)) {
                AppButton(stringResource(R.string.calllog_retry_report), onClick = onReport)
            }
        }
    }
}
