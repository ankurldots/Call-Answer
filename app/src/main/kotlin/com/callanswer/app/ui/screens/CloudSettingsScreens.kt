package com.callanswer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.callanswer.app.R
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.api.Profile
import com.callanswer.app.data.Conflict
import com.callanswer.app.data.ConflictChoice
import com.callanswer.app.data.SyncResult
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.AppSwitchRow
import com.callanswer.app.ui.components.AppTextField
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.ChipRow
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.model.FallbackRoute
import com.callanswer.core.model.Language
import com.callanswer.core.model.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Cloud Agent settings of Requirement 20: the Agent on/off switch (available only after the Privacy_Consent is
 * accepted), User_Mobile for Take_Over, greeting name, default language, Fallback_Route, recording, retention, cost
 * rates and the monthly limit. Saves go through [CloudSettingsState] (cache, pending edit, `PUT /settings`).
 */
@Composable
fun AgentSettingsScreen(
    api: CompanionApi,
    state: CloudSettingsState,
    onOpenConsent: () -> Unit,
    onOpenConflicts: () -> Unit,
    onBack: () -> Unit,
) {
    var consent by remember { mutableStateOf<ConsentStatus>(ConsentStatus.Loading) }
    var profile by remember { mutableStateOf<Profile?>(null) }
    LaunchedEffect(api) {
        consent = try {
            val me = api.me()
            profile = me
            if (me.consentAccepted) ConsentStatus.Accepted else ConsentStatus.NotAccepted
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            ConsentStatus.Failed(consentErrorText(e))
        }
    }
    CloudSettingsPage(stringResource(R.string.settings_section_agent), state, onOpenConflicts, onBack) {
        AgentForm(state, it, consent, profile, onOpenConsent)
    }
}

@Composable
private fun AgentForm(state: CloudSettingsState, initial: Settings, consent: ConsentStatus, profile: Profile?, onOpenConsent: () -> Unit) {
    val scope = rememberCoroutineScope()
    val live = state.settings ?: initial
    var draft by remember { mutableStateOf(AgentDraft.from(initial)) }
    var errors by remember { mutableStateOf<Map<AgentField, String>>(emptyMap()) }
    var outcome by remember { mutableStateOf<SaveOutcome?>(null) }
    var toggleFailed by remember { mutableStateOf(false) }

    AppSwitchRow(
        stringResource(R.string.agent_enabled), live.agentEnabled,
        { scope.launch { toggleFailed = !state.change { s -> s.copy(agentEnabled = it) } } },
        enabled = agentSwitchEnabled(consent, live.agentEnabled) &&
            (agentMayTurnOn(true, profile, live.userMobile) || live.agentEnabled),
    )
    if (profile != null && consent == ConsentStatus.Accepted && !agentMayTurnOn(true, profile, live.userMobile)) {
        StatusBanner(stringResource(R.string.agent_prerequisites_missing), BannerSeverity.WARNING)
    }
    when (consent) {
        ConsentStatus.Loading -> Text(stringResource(R.string.settings_loading), style = MaterialTheme.typography.bodySmall)
        is ConsentStatus.Failed -> StatusBanner(stringResource(consent.messageRes), BannerSeverity.ERROR)
        ConsentStatus.NotAccepted -> {
            StatusBanner(stringResource(R.string.agent_consent_locked), BannerSeverity.WARNING)
            AppButton(stringResource(R.string.fwd_open_consent), onClick = onOpenConsent)
        }
        ConsentStatus.Accepted -> Unit
    }
    AppTextField(
        draft.userMobile, { draft = draft.copy(userMobile = it) }, stringResource(R.string.agent_user_mobile),
        errorMessage = errors[AgentField.USER_MOBILE],
    )
    AppTextField(draft.greetingName, { draft = draft.copy(greetingName = it) }, stringResource(R.string.agent_greeting_name))

    val en = stringResource(R.string.agent_lang_en)
    val hi = stringResource(R.string.agent_lang_hi)
    Text(stringResource(R.string.agent_language), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    ChipRow(
        labels = listOf(en, hi),
        selected = setOf(if (draft.defaultLanguage == Language.EN) en else hi),
        onChipClick = { draft = draft.copy(defaultLanguage = if (it == en) Language.EN else Language.HI) },
    )

    val voicemail = stringResource(R.string.agent_fallback_voicemail)
    val forward = stringResource(R.string.agent_fallback_forward)
    Text(stringResource(R.string.agent_fallback), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    ChipRow(
        labels = listOf(voicemail, forward),
        selected = setOf(if (draft.fallbackRoute == FallbackRoute.VOICEMAIL) voicemail else forward),
        onChipClick = { draft = draft.copy(fallbackRoute = if (it == voicemail) FallbackRoute.VOICEMAIL else FallbackRoute.FORWARD_TO_MOBILE) },
    )

    AppSwitchRow(stringResource(R.string.agent_recording), draft.recordingEnabled, { draft = draft.copy(recordingEnabled = it) })
    if (disclosureOffWarning(live.copy(recordingEnabled = draft.recordingEnabled))) {
        StatusBanner(stringResource(R.string.settings_disclosure_off_warning), BannerSeverity.WARNING)
    }
    AppTextField(
        draft.retentionDays, { draft = draft.copy(retentionDays = it) },
        stringResource(R.string.agent_retention, RETENTION_MIN_DAYS, RETENTION_MAX_DAYS),
        errorMessage = errors[AgentField.RETENTION],
    )

    Text(stringResource(R.string.agent_rates_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    AppTextField(
        draft.exotelPaise, { draft = draft.copy(exotelPaise = it) }, stringResource(R.string.agent_rate_exotel),
        errorMessage = errors[AgentField.RATE_EXOTEL],
    )
    AppTextField(
        draft.sonicInPaise, { draft = draft.copy(sonicInPaise = it) }, stringResource(R.string.agent_rate_sonic_in),
        errorMessage = errors[AgentField.RATE_SONIC_IN],
    )
    AppTextField(
        draft.sonicOutPaise, { draft = draft.copy(sonicOutPaise = it) }, stringResource(R.string.agent_rate_sonic_out),
        errorMessage = errors[AgentField.RATE_SONIC_OUT],
    )
    AppTextField(
        draft.monthlyLimitInr, { draft = draft.copy(monthlyLimitInr = it) }, stringResource(R.string.agent_monthly_limit),
        errorMessage = errors[AgentField.MONTHLY_LIMIT],
    )

    AppSwitchRow(stringResource(R.string.ring_through_enabled), draft.ringThroughEnabled, { draft = draft.copy(ringThroughEnabled = it) })
    Text(stringResource(R.string.ring_through_explanation), style = MaterialTheme.typography.bodySmall)
    AppTextField(
        draft.ringThroughWindowMinutes, { draft = draft.copy(ringThroughWindowMinutes = it) },
        stringResource(R.string.ring_through_window),
        errorMessage = errors[AgentField.RING_THROUGH_WINDOW],
    )
    AppTextField(
        draft.ringThroughMaxSeconds, { draft = draft.copy(ringThroughMaxSeconds = it) },
        stringResource(R.string.ring_through_max),
        errorMessage = errors[AgentField.RING_THROUGH_MAX],
    )
    ScreeningRoleRow()
    SilenceNonContactsRow()
    SaveStatus(outcome, toggleFailed)
    AppButton(stringResource(R.string.settings_save), onClick = {
        scope.launch {
            when (val r = draft.applyTo(state.settings ?: initial)) {
                is AgentDraftResult.Invalid -> { errors = r.errors; outcome = SaveOutcome.Rejected(emptyMap()) }
                is AgentDraftResult.Ok -> { errors = emptyMap(); outcome = state.save(r.settings).toSaveOutcome() }
            }
        }
    })
}

@Composable
private fun fieldLabel(field: String): String = when (field) {
    "agentEnabled" -> stringResource(R.string.agent_enabled)
    "userMobile" -> stringResource(R.string.agent_user_mobile)
    "greetingName" -> stringResource(R.string.agent_greeting_name)
    "defaultLanguage" -> stringResource(R.string.agent_language)
    "fallbackRoute" -> stringResource(R.string.agent_fallback)
    "recordingEnabled" -> stringResource(R.string.agent_recording)
    "retentionDays" -> stringResource(R.string.agent_retention_short)
    "costRates" -> stringResource(R.string.agent_rates_title)
    "ringThroughEnabled" -> stringResource(R.string.ring_through_enabled)
    "ringThroughWindowMinutes" -> stringResource(R.string.ring_through_window)
    "ringThroughMaxSeconds" -> stringResource(R.string.ring_through_max)
    "monthlyLimitInr" -> stringResource(R.string.agent_monthly_limit)
    "voiceGender", "voiceId" -> stringResource(R.string.settings_voice)
    "disclosureEnabled" -> stringResource(R.string.settings_disclosure)
    "agentName" -> stringResource(R.string.settings_agent_name)
    "mapLocation" -> stringResource(R.string.settings_map_location)
    "transferTarget" -> stringResource(R.string.settings_transfer_target)
    "gates" -> stringResource(R.string.settings_section_gates)
    "deliverySteps" -> stringResource(R.string.settings_section_steps)
    "quickReplies" -> stringResource(R.string.settings_quick_replies)
    else -> field
}

/**
 * Per-field choice for settings changed on this phone and on the server with different values. Fields changed on only
 * one side were merged already. The choice is applied with [com.callanswer.app.data.SettingsRepository.resolve].
 */
@Composable
fun SettingsConflictsScreen(state: CloudSettingsState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var conflicts by remember { mutableStateOf<List<Conflict>?>(null) }
    var message by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val choices = remember { mutableStateMapOf<String, ConflictChoice>() }

    fun show(result: SyncResult): Boolean = when (result) {
        is SyncResult.NeedsResolution -> {
            conflicts = result.conflicts
            result.conflicts.forEach { choices.putIfAbsent(it.field, ConflictChoice.KEEP_SERVER) }
            message = null
            false
        }
        is SyncResult.Saved, is SyncResult.UpToDate -> true
        is SyncResult.Failed -> { message = R.string.conflicts_offline; false }
        is SyncResult.Invalid -> { message = R.string.conflicts_rejected; false }
        SyncResult.RetriesExhausted -> { message = R.string.conflicts_retry_later; false }
    }

    suspend fun finish(result: SyncResult) {
        if (result is SyncResult.Invalid) state.repo.discardPending()
        if (show(result)) { state.refresh(); onBack() }
    }

    LaunchedEffect(state) { finish(state.repo.sync()) }

    SettingsPage(stringResource(R.string.conflicts_title), onBack) {
        Text(stringResource(R.string.conflicts_intro), style = MaterialTheme.typography.bodyMedium)
        message?.let { StatusBanner(stringResource(it), BannerSeverity.WARNING) }
        val list = conflicts
        if (list == null) {
            Text(stringResource(R.string.settings_loading))
        } else {
            val mine = stringResource(R.string.conflicts_keep_mine)
            val server = stringResource(R.string.conflicts_keep_server)
            list.forEach { c ->
                val label = fieldLabel(c.field)
                AppCard(contentDescription = label) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.conflicts_mine_value, displayValue(c.mine)), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.conflicts_server_value, displayValue(c.server)), style = MaterialTheme.typography.bodyMedium)
                        ChipRow(
                            labels = listOf(mine, server),
                            selected = setOf(if (choices[c.field] == ConflictChoice.KEEP_MINE) mine else server),
                            onChipClick = { choices[c.field] = if (it == mine) ConflictChoice.KEEP_MINE else ConflictChoice.KEEP_SERVER },
                        )
                    }
                }
            }
            AppButton(stringResource(R.string.conflicts_apply), enabled = !busy && list.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    try { finish(state.repo.resolve(choices.toMap())) } finally { busy = false }
                }
            })
        }
    }
}
