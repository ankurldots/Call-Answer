package com.callanswer.app.ui.screens
import com.callanswer.app.data.rememberAgentName
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.Profile
import com.callanswer.app.auth.AuthState
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import com.callanswer.app.R
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.IconBadge
import com.callanswer.app.ui.components.SectionHeader
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import kotlinx.coroutines.launch
/** Home: setup progress, quick links, then every denied item with "Request again" and "Open settings" (9.7). */
@Composable
fun HomeScreen(
    model: SetupModel,
    actions: SetupActions,
    resumeTick: Int = 0,
    onOpenSettings: () -> Unit = {},
    onOpenCallLog: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
) {
    val status = model.status
    val total = SetupItem.entries.size
    val ready = total - status.denied.size
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDimens.spaceMd)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd),
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        AgentReadinessCard(resumeTick, onOpenAccount, onOpenOnboarding)
        SetupCard(ready, total)
        SectionHeader(stringResource(R.string.home_quick_links))
        QuickLink(Icons.Filled.Call, stringResource(R.string.calllog_open), stringResource(R.string.calllog_open_hint), onOpenCallLog)
        QuickLink(Icons.Filled.Settings, stringResource(R.string.settings_open), stringResource(R.string.settings_open_hint), onOpenSettings)
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
private fun SetupCard(ready: Int, total: Int) {
    AppCard(contentDescription = stringResource(R.string.app_name)) {
        Text(
            if (ready == total) stringResource(R.string.home_setup_complete)
            else stringResource(R.string.home_setup_progress, ready, total),
            style = MaterialTheme.typography.labelLarge,
        )
        LinearProgressIndicator(progress = { ready.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
}
@Composable
private fun QuickLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    AppCard(contentDescription = "$title. $subtitle", onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceMd)) {
            IconBadge(icon, size = AppDimens.iconLg + AppDimens.spaceSm)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
@Composable
private fun DeniedItem(item: SetupItem, actions: SetupActions) {
    val message = deniedMessage(item)
    AppCard(contentDescription = message) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceMd)) {
            IconBadge(item.icon(), size = AppDimens.iconLg + AppDimens.spaceSm)
            Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall)
        }
        StatusBanner(message, BannerSeverity.WARNING, Modifier.padding(vertical = AppDimens.spaceSm))
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm), modifier = Modifier.fillMaxWidth()) {
            AppButton(stringResource(R.string.action_request_again), onClick = { actions.request(item) })
            AppButton(stringResource(R.string.action_open_settings), onClick = { actions.openSettings(item) }, primary = false)
        }
    }
}

/**
 * What the Agent still needs (Requirement 31 criterion 6): sign-in, consent, an assigned number, and the User_Mobile.
 * "Continue setup" reopens the onboarding at the first missing step.
 */
@Composable
private fun AgentReadinessCard(resumeTick: Int, onOpenAccount: () -> Unit, onOpenOnboarding: () -> Unit) {
    val app = LocalContext.current.applicationContext as CallAnswerApp
    val authState by app.authRepository.state.collectAsState()
    val signedIn = authState is AuthState.SignedIn
    val settings = rememberCloudSettings()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(signedIn, resumeTick) {
        if (!signedIn) { profile = null; return@LaunchedEffect }
        loadFailed = false
        try {
            profile = app.companionApi.me()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ApiException) {
            loadFailed = true
        }
    }
    val missing = missingPrerequisites(signedIn, profile, settings.settings?.userMobile)
    AppCard(contentDescription = stringResource(R.string.home_agent_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            Text(stringResource(R.string.home_agent_title), style = MaterialTheme.typography.titleMedium)
            when {
                !signedIn -> {
                    StatusBanner(stringResource(R.string.home_signin_missing), BannerSeverity.WARNING)
                    AppButton(stringResource(R.string.action_sign_in), onClick = onOpenAccount)
                }
                profile == null -> {
                    if (loadFailed) StatusBanner(stringResource(R.string.fwd_err_network), BannerSeverity.ERROR)
                    else Text(stringResource(R.string.fwd_loading))
                }
                missing.isNotEmpty() -> {
                    missing.forEach { StatusBanner(stringResource(prerequisiteText(it)), BannerSeverity.WARNING) }
                    AppButton(stringResource(R.string.home_continue_setup), onClick = onOpenOnboarding)
                }
                else -> StatusBanner(
                    stringResource(if (settings.settings?.agentEnabled == true) R.string.home_agent_on else R.string.home_agent_off),
                )
            }
        }
    }
    if (signedIn) SettingsUploadOffer(settings)
}

private fun prerequisiteText(p: AgentPrerequisite): Int = when (p) {
    AgentPrerequisite.SIGN_IN -> R.string.home_signin_missing
    AgentPrerequisite.CONSENT -> R.string.home_need_consent
    AgentPrerequisite.NUMBER -> R.string.home_need_number
    AgentPrerequisite.USER_MOBILE -> R.string.home_need_mobile
}
