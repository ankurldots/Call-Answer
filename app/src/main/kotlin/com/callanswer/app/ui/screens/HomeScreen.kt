package com.callanswer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.callanswer.app.R
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens

/** Home: screening status, then every denied item with "Request again" and "Open settings" (9.7). */
@Composable
fun HomeScreen(model: SetupModel, actions: SetupActions, onOpenSettings: () -> Unit = {}, onOpenCallLog: () -> Unit = {}) {
    val status = model.status
    val screeningActive = model.screeningEnabled &&
        status.isGranted(SetupItem.CALL_SCREENING_ROLE) &&
        status.isGranted(SetupItem.CONTACTS)
    val statusText = when {
        !model.screeningEnabled -> R.string.home_status_off_setting
        !status.isGranted(SetupItem.CALL_SCREENING_ROLE) -> R.string.home_status_off_role
        !status.isGranted(SetupItem.CONTACTS) -> R.string.home_status_off_contacts
        else -> R.string.home_status_on
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        StatusBanner(
            stringResource(statusText),
            if (screeningActive) BannerSeverity.INFO else BannerSeverity.WARNING,
        )
        AppButton(stringResource(R.string.calllog_open), onClick = onOpenCallLog)
        AppButton(stringResource(R.string.settings_open), onClick = onOpenSettings, primary = false)
        if (!status.isGranted(SetupItem.RECORDING_FOLDER)) {
            StatusBanner(stringResource(R.string.home_no_folder_warning), BannerSeverity.WARNING)
        }

        if (status.denied.isNotEmpty()) {
            Text(
                stringResource(R.string.home_denied_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            status.denied.forEach { item -> DeniedItem(item, actions) }
        }
    }
}

@Composable
private fun DeniedItem(item: SetupItem, actions: SetupActions) {
    val message = deniedMessage(item)
    AppCard(contentDescription = message) {
        Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall)
        StatusBanner(message, BannerSeverity.WARNING, Modifier.padding(vertical = AppDimens.spaceSm))
        Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            AppButton(stringResource(R.string.action_request_again), onClick = { actions.request(item) })
            AppButton(stringResource(R.string.action_open_settings), onClick = { actions.openSettings(item) }, primary = false)
        }
    }
}
