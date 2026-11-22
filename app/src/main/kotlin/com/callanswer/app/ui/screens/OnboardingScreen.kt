package com.callanswer.app.ui.screens

import com.callanswer.app.data.rememberAgentName
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.callanswer.app.R
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.IconBadge
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens

/**
 * Guided setup. Each step shows its purpose text first; the system request only starts when the User
 * taps the button (9.2). A denial disables the dependent feature and shows a banner naming it (9.3).
 */
@Composable
fun OnboardingScreen(model: SetupModel, actions: SetupActions, onFinished: () -> Unit) {
    val steps = SetupItem.entries
    var index by rememberSaveable { mutableIntStateOf(0) }
    val item = steps[index]
    val granted = model.status.isGranted(item)
    val isLast = index == steps.lastIndex

    fun next() {
        if (isLast) {
            model.finishOnboarding()
            onFinished()
        } else {
            index += 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd),
    ) {
        val progressText = stringResource(R.string.onboarding_progress, index + 1, steps.size)
        Text(progressText, style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            progress = { (index + 1).toFloat() / steps.size },
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = progressText
            },
        )
        IconBadge(item.icon(), modifier = Modifier.align(Alignment.CenterHorizontally))
        Text(
            stringResource(item.title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        AppCard(contentDescription = stringResource(item.purpose, rememberAgentName())) {
            Text(stringResource(item.purpose, rememberAgentName()), style = MaterialTheme.typography.bodyLarge)
        }
        StepHelp(item, actions)

        if (item in model.deniedThisSession && !granted) {
            StatusBanner(deniedMessage(item), BannerSeverity.WARNING)
        }
        AnimatedVisibility(granted && item != SetupItem.AUTO_RECORD) {
            StatusBanner(stringResource(R.string.onboarding_granted), BannerSeverity.INFO)
        }

        if (item == SetupItem.AUTO_RECORD) {
            AppButton(stringResource(R.string.onboarding_got_it), onClick = { model.acknowledgeAutoRecord(); next() })
        } else if (granted) {
            AppButton(stringResource(R.string.onboarding_next), onClick = ::next)
        } else {
            AppButton(stringResource(actionLabel(item)), onClick = { actions.request(item) })
            AppButton(stringResource(R.string.onboarding_skip), onClick = ::next, primary = false)
        }
    }
}

/** Extra guidance for the steps that need it. */
@Composable
private fun StepHelp(item: SetupItem, actions: SetupActions) {
    when (item) {
        SetupItem.ACCESSIBILITY -> {
            Text(stringResource(R.string.setup_accessibility_restricted_help), style = MaterialTheme.typography.bodyMedium)
            AppButton(stringResource(R.string.setup_open_app_info), onClick = actions::openAppInfo, primary = false)
        }
        SetupItem.BATTERY -> Text(stringResource(R.string.setup_battery_help), style = MaterialTheme.typography.bodyMedium)
        SetupItem.RECORDING_FOLDER -> Text(stringResource(R.string.setup_folder_help), style = MaterialTheme.typography.bodyMedium)
        else -> Unit
    }
}

private fun actionLabel(item: SetupItem): Int = when (item) {
    SetupItem.CALL_SCREENING_ROLE -> R.string.action_set_role
    SetupItem.ACCESSIBILITY, SetupItem.FULL_SCREEN_INTENT, SetupItem.BATTERY -> R.string.action_open_settings
    SetupItem.RECORDING_FOLDER -> R.string.action_choose_folder
    else -> R.string.action_allow
}

/** "<feature> is disabled because <permission> was not granted." Shared by onboarding and Home. */
@Composable
fun deniedMessage(item: SetupItem): String =
    stringResource(R.string.banner_denied, stringResource(item.feature), stringResource(item.permissionLabel))
