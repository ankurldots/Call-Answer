package com.callanswer.app.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import com.callanswer.app.ui.components.IconBadge
import com.callanswer.app.ui.components.ScreenHeader
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
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.MonthUsage
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import kotlinx.coroutines.delay
import com.callanswer.app.report.ReportSpamActivity
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.model.CallLogEntry
import com.callanswer.core.model.ReportStatus

internal fun Context.callAnswerApp(): CallAnswerApp = applicationContext as CallAnswerApp

/** Opens the spam report flow of task 31.3 for one entry. */
internal fun startReportSpam(context: Context, entryId: String) {
    context.startActivity(
        Intent(context, ReportSpamActivity::class.java).putExtra(ReportSpamActivity.EXTRA_ENTRY_ID, entryId),
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
    var usage by remember { mutableStateOf<MonthUsage?>(null) }
    var refreshFailed by remember { mutableStateOf(false) }
    // Show the cache at once, then read the server every POLL_MS so a finished call is listed within 10 seconds (Requirement 19 criterion 10).
    LaunchedEffect(resumeTick) {
        entries = CallLogFormat.newestFirst(dao.list())
        val app = context.callAnswerApp()
        while (true) {
            try {
                entries = CallLogFormat.newestFirst(app.callLogRepository.refresh())
                refreshFailed = false
            } catch (e: ApiException) {
                refreshFailed = true
            }
            try { usage = app.companionApi.usageMonth() } catch (e: ApiException) { /* keep the last total */ }
            delay(POLL_MS)
        }
    }

    Column(Modifier.fillMaxSize().padding(AppDimens.spaceMd), verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd)) {
        ScreenHeader(stringResource(R.string.calllog_title), onBack)
        MonthCostCard(usage)
        if (refreshFailed) StatusBanner(stringResource(R.string.calllog_refresh_failed), BannerSeverity.WARNING)
        val list = entries
        when {
            list == null -> {
                val loading = stringResource(R.string.calllog_loading)
                Box(Modifier.fillMaxSize().semantics { contentDescription = loading }, contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            list.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(AppDimens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconBadge(Icons.Filled.Call)
                Text(stringResource(R.string.calllog_empty), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.calllog_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
                items(list, key = { it.id }) { entry ->
                    CallLogRow(entry, onOpen = { onOpenEntry(entry.id) }, onReport = { startReportSpam(context, entry.id) })
                }
            }
        }
    }
}
private const val POLL_MS = 5_000L

/** Month total in INR, labeled as an estimate (22.2, 22.7). */
@Composable
private fun MonthCostCard(usage: MonthUsage?) {
    AppCard(contentDescription = stringResource(R.string.cost_month_title)) {
        Text(stringResource(R.string.cost_month_title), style = MaterialTheme.typography.titleSmall)
        if (usage == null) {
            Text(stringResource(R.string.cost_month_unavailable))
        } else {
            Text(
                stringResource(R.string.cost_month_total, usage.month, CallDetailFormat.inr(usage.totalPaise)),
                style = MaterialTheme.typography.titleMedium,
            )
            usage.monthlyLimitInr?.let { Text(stringResource(R.string.cost_month_limit, it)) }
        }
        Text(
            stringResource(R.string.cost_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceMd)) {
            IconBadge(Icons.Filled.Call, size = AppDimens.iconLg + AppDimens.spaceSm)
            Column(Modifier.weight(1f)) {
                Text(number, style = MaterialTheme.typography.titleMedium)
                Text(whenText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm),
            modifier = Modifier.padding(top = AppDimens.spaceSm),
        ) {
            AssistChip(onClick = {}, label = { Text(CallLogFormat.label(entry.outcome)) })
            AssistChip(onClick = {}, label = { Text(stringResource(R.string.calllog_report_chip, status)) })
            entry.costEstimatePaise?.let {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.cost_row_estimate, CallDetailFormat.inr(it))) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            AppButton(stringResource(R.string.calllog_details), onClick = onOpen, primary = false)
            AppButton(stringResource(R.string.calllog_report_spam), onClick = onReport)
            if (CallLogFormat.showRetry(entry.report.status)) {
                AppButton(stringResource(R.string.calllog_retry_report), onClick = onReport)
            }
        }
    }
}
