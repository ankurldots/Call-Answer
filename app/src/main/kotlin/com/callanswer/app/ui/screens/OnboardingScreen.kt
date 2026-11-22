package com.callanswer.app.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.R
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.Profile
import com.callanswer.app.auth.AuthState
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppSwitchRow
import com.callanswer.app.ui.components.AppTextField
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guided start after the first sign-in (Requirement 31): sign-in, Privacy_Consent, the assigned number (or "waiting for
 * the admin"), the User_Mobile, the forwarding setup, and the settings. The step is derived from the server state
 * ([onboardingStep]), so the flow resumes where the User stopped. The Agent stays off until consent, number, and
 * User_Mobile exist.
 */
@Composable
fun OnboardingScreen(
    model: SetupModel,
    actions: SetupActions,
    onOpenSettings: () -> Unit,
    onOpenTurnOff: () -> Unit,
    onOpenTest: () -> Unit,
    onFinished: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as CallAnswerApp
    val api = app.companionApi
    val auth = app.authRepository
    val authState by auth.state.collectAsState()
    // A build without the account feature has nothing to sign in to.
    val signedIn = authState is AuthState.SignedIn
    val settings = rememberCloudSettings()
    var profile by remember { mutableStateOf<Profile?>(null) }
    var loadError by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var numberSeen by rememberSaveable { mutableStateOf(false) }
    var forwardingDone by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(signedIn, reload) {
        if (!signedIn) { profile = null; return@LaunchedEffect }
        loadError = null
        try {
            profile = api.me()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            loadError = if (e is ApiException.Network) R.string.fwd_err_network else R.string.fwd_err_other
        }
        settings.refresh()
    }

    val me = profile
    val step = onboardingStep(signedIn, me, settings.settings?.userMobile, numberSeen, forwardingDone)
    // Polls the profile while the waiting screen is open, at most once per interval (Requirement 31 criterion 2).
    val waiting = step == OnboardingStep.NUMBER && me != null && me.virtualNumber.isNullOrBlank()
    LaunchedEffect(waiting) {
        while (waiting) {
            delay(NUMBER_POLL_INTERVAL_MS)
            try {
                profile = api.me()
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiException) {
                // Keep waiting; the next poll tries again.
            }
        }
    }

    // Steps that are full screens of their own.
    when {
        step == OnboardingStep.CONSENT && me != null -> {
            ConsentScreen(api, onBack = null, onAccepted = { reload++ })
            return
        }
        step == OnboardingStep.FORWARDING -> {
            ForwardingSetupScreen(
                api, onBack = null, onOpenConsent = { reload++ }, onOpenTurnOff = onOpenTurnOff, onOpenTest = onOpenTest,
                footer = { AppButton(stringResource(R.string.onboarding_next), onClick = { forwardingDone = true }) },
            )
            return
        }
    }

    val index = step.ordinal + 1
    val total = OnboardingStep.entries.size
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd),
    ) {
        val progressText = stringResource(R.string.onboarding_progress, index, total)
        Text(progressText, style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            progress = { index.toFloat() / total },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressText },
        )
        if (signedIn) SettingsUploadOffer(settings)
        loadError?.let {
            StatusBanner(stringResource(it), BannerSeverity.ERROR)
            AppButton(stringResource(R.string.fwd_retry), primary = false, onClick = { reload++ })
        }
        when (step) {
            OnboardingStep.SIGN_IN -> SignInStep(auth)
            OnboardingStep.CONSENT -> Text(stringResource(R.string.consent_loading))
            OnboardingStep.NUMBER -> NumberStep(me, onContinue = { numberSeen = true })
            OnboardingStep.MOBILE -> MobileStep(settings, me?.virtualNumber)
            OnboardingStep.FORWARDING -> Unit
            OnboardingStep.SETTINGS -> SettingsStep(model, actions, settings, me, onOpenSettings) {
                model.finishOnboarding()
                onFinished()
            }
        }
    }
}

@Composable
private fun StepTitle(@androidx.annotation.StringRes title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

@Composable
private fun SignInStep(auth: com.callanswer.app.auth.AuthRepository) {
    StepTitle(R.string.onboarding_signin_title)
    Text(stringResource(R.string.onboarding_signin_body), style = MaterialTheme.typography.bodyLarge)
    AccountPanel(auth)
}

/** Shows the assigned number, or says that the Admin has not assigned one yet (no forwarding codes in that case). */
@Composable
private fun NumberStep(profile: Profile?, onContinue: () -> Unit) {
    StepTitle(R.string.onboarding_number_title)
    val number = profile?.virtualNumber
    when {
        profile == null -> Text(stringResource(R.string.fwd_loading))
        number.isNullOrBlank() -> {
            StatusBanner(stringResource(R.string.onboarding_number_waiting), BannerSeverity.WARNING)
            Text(stringResource(R.string.onboarding_number_waiting_hint), style = MaterialTheme.typography.bodyMedium)
        }
        else -> {
            Text(stringResource(R.string.fwd_your_number, number), style = MaterialTheme.typography.titleLarge)
            AppButton(stringResource(R.string.onboarding_next), onClick = onContinue)
        }
    }
}

/** User_Mobile entry with validation; saved through the settings sync. */
@Composable
private fun MobileStep(settings: CloudSettingsState, virtualNumber: String?) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf(settings.settings?.userMobile.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<EditOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    StepTitle(R.string.onboarding_mobile_title)
    Text(stringResource(R.string.onboarding_mobile_body), style = MaterialTheme.typography.bodyLarge)
    AppTextField(text, { text = it }, stringResource(R.string.agent_user_mobile), errorMessage = error)
    when (val o = outcome) {
        is EditOutcome.Rejected -> StatusBanner(stringResource(R.string.settings_server_rejected, o.field), BannerSeverity.ERROR)
        EditOutcome.NotYetApplied -> StatusBanner(stringResource(R.string.settings_not_applied), BannerSeverity.WARNING)
        EditOutcome.NeedsChoice -> StatusBanner(stringResource(R.string.settings_needs_choice), BannerSeverity.WARNING)
        else -> Unit
    }
    AppButton(stringResource(R.string.onboarding_mobile_save), enabled = !busy && settings.loaded, onClick = {
        val message = userMobileError(text, virtualNumber)
        error = message
        if (message != null) return@AppButton
        busy = true
        scope.launch {
            outcome = settings.save((settings.settings ?: com.callanswer.core.model.Settings()).copy(userMobile = text.trim()))
            busy = false
        }
    })
}

/** Last step: the Agent switch (only when every prerequisite is met), device permissions, and the full settings. */
@Composable
private fun SettingsStep(
    model: SetupModel,
    actions: SetupActions,
    settings: CloudSettingsState,
    profile: Profile?,
    onOpenSettings: () -> Unit,
    onFinish: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val live = settings.settings
    var failed by remember { mutableStateOf(false) }
    StepTitle(R.string.onboarding_settings_title)
    Text(stringResource(R.string.onboarding_settings_body), style = MaterialTheme.typography.bodyLarge)
    val may = agentMayTurnOn(true, profile, live?.userMobile)
    AppSwitchRow(
        stringResource(R.string.agent_enabled), live?.agentEnabled == true,
        { on -> scope.launch { failed = !settings.change { it.copy(agentEnabled = on) } } },
        enabled = live != null && (may || live.agentEnabled),
    )
    if (!may) StatusBanner(stringResource(R.string.agent_prerequisites_missing), BannerSeverity.WARNING)
    if (failed) StatusBanner(stringResource(R.string.settings_save_failed), BannerSeverity.ERROR)
    // Alerts for Take over need the notification permissions; each one is asked for with its purpose text.
    model.status.denied.forEach { item ->
        Text(stringResource(item.purpose), style = MaterialTheme.typography.bodyMedium)
        AppButton(
            stringResource(if (item == SetupItem.FULL_SCREEN_INTENT) R.string.action_open_settings else R.string.action_allow),
            primary = false, onClick = { actions.request(item) },
        )
    }
    AppButton(stringResource(R.string.settings_open), primary = false, onClick = onOpenSettings)
    AppButton(stringResource(R.string.onboarding_finish), onClick = onFinish)
}

/** "<feature> is turned off because <permission> was not granted." Shared by Home. */
@Composable
fun deniedMessage(item: SetupItem): String =
    stringResource(R.string.banner_denied, stringResource(item.feature), stringResource(item.permissionLabel))
