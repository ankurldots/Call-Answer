package com.callanswer.app.ui.screens

import com.callanswer.app.data.displayAgentName
import com.callanswer.app.data.rememberAgentName
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import com.callanswer.app.ui.components.IconBadge
import com.callanswer.app.ui.components.ScreenHeader
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.callanswer.app.R
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.data.UpdateResult
import com.callanswer.app.tts.VoiceSamplePlayer
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.AppSwitchRow
import com.callanswer.app.ui.components.AppTextField
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.ChipRow
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.model.Language
import com.callanswer.core.model.QuickReply
import com.callanswer.core.model.Settings
import com.callanswer.core.model.VoiceGender
import kotlinx.coroutines.launch

/** Outcome of a save through [SettingsStore.update]. A rejection leaves the stored values unchanged. */
sealed interface SaveOutcome {
    data object Saved : SaveOutcome
    data class Rejected(val fieldErrors: Map<SettingsField, String>) : SaveOutcome
}

/** Validates the draft, then saves it against the latest stored settings. */
suspend fun SettingsStore.commit(build: (Settings) -> DraftResult): SaveOutcome =
    when (val draft = build(current())) {
        is DraftResult.Invalid -> SaveOutcome.Rejected(draft.errors)
        is DraftResult.Ok -> when (update(draft.settings)) {
            UpdateResult.Saved -> SaveOutcome.Saved
            is UpdateResult.Rejected -> SaveOutcome.Rejected(emptyMap())
        }
    }

/** Saves a toggle-style change. Returns false when the store rejected it. */
private suspend fun SettingsStore.change(f: (Settings) -> Settings): Boolean =
    update(f(current())) == UpdateResult.Saved

@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AppDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(AppDimens.spaceMd),
    ) {
        ScreenHeader(title, onBack)
        content()
    }
}

@Composable
internal fun SaveStatus(outcome: SaveOutcome?, failed: Boolean = false) {
    when {
        failed -> StatusBanner(stringResource(R.string.settings_save_failed), BannerSeverity.ERROR)
        outcome == SaveOutcome.Saved -> StatusBanner(stringResource(R.string.settings_saved))
        outcome is SaveOutcome.Rejected -> StatusBanner(stringResource(R.string.settings_not_saved), BannerSeverity.ERROR)
    }
}

/** Loads the stored settings once for form drafts. */
@Composable
internal fun rememberInitialSettings(store: SettingsStore): Settings? =
    produceState<Settings?>(null, store) { value = store.current() }.value

/** List of the settings sections. */
@Composable
fun SettingsHubScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    SettingsPage(stringResource(R.string.settings_title), onBack) {
        listOf(
            Triple(Routes.SETTINGS_GENERAL, R.string.settings_section_general, Icons.Filled.Settings),
            Triple(Routes.SETTINGS_GATES, R.string.settings_section_gates, Icons.Filled.Lock),
            Triple(Routes.SETTINGS_CALL, R.string.settings_section_call, Icons.Filled.Call),
            Triple(Routes.SETTINGS_STEPS, R.string.settings_section_steps, Icons.Filled.Send),
            Triple(Routes.SETTINGS_RECORDING, R.string.settings_section_recording, Icons.Filled.Home),
            Triple(Routes.SETTINGS_SPAM, R.string.settings_section_spam, Icons.Filled.Warning),
            Triple(Routes.SETTINGS_ACCOUNT, R.string.settings_section_account, Icons.Filled.Person),
        ).forEach { (route, label, icon) ->
            val text = stringResource(label)
            AppCard(contentDescription = text, onClick = { onOpen(route) }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceMd),
                ) {
                    IconBadge(icon, size = AppDimens.iconLg + AppDimens.spaceSm)
                    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

/** Screening, Location URL, Address, Transfer_Target, maximum stalling, protected terms, cloud LLM (1.7, 9.1, 9.4, 9.6, 13.x). */
@Composable
fun GeneralSettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val initial = rememberInitialSettings(store)
    SettingsPage(stringResource(R.string.settings_section_general), onBack) {
        if (initial != null) GeneralForm(store, initial)
    }
}

@Composable
private fun GeneralForm(store: SettingsStore, initial: Settings) {
    val scope = rememberCoroutineScope()
    val live by store.settings.collectAsState(initial = initial)
    var draft by remember { mutableStateOf(GeneralDraft.from(initial)) }
    var outcome by remember { mutableStateOf<SaveOutcome?>(null) }
    var toggleFailed by remember { mutableStateOf(false) }
    val errors = (outcome as? SaveOutcome.Rejected)?.fieldErrors.orEmpty()

    AppSwitchRow(stringResource(R.string.settings_screening), live.screeningEnabled, {
        scope.launch { toggleFailed = !store.change { s -> s.copy(screeningEnabled = it) } }
    })
    AppTextField(
        draft.agentName, { draft = draft.copy(agentName = it) },
        stringResource(R.string.settings_agent_name),
    )
    AppTextField(
        draft.mapLocation, { draft = draft.copy(mapLocation = it) },
        stringResource(R.string.settings_map_location),
        errorMessage = errors[SettingsField.LOCATION_URL],
    )
    AppTextField(
        draft.address, { draft = draft.copy(address = it) },
        stringResource(R.string.settings_address),
        singleLine = false,
    )
    AppTextField(
        draft.addressHi, { draft = draft.copy(addressHi = it) },
        stringResource(R.string.settings_address_hi),
        singleLine = false,
    )
    Text(stringResource(R.string.settings_location_hint, rememberAgentName()), style = MaterialTheme.typography.bodySmall)
    AppTextField(
        draft.transferTarget, { draft = draft.copy(transferTarget = it) },
        stringResource(R.string.settings_transfer_target),
        errorMessage = errors[SettingsField.TRANSFER_TARGET],
    )
    AppTextField(
        draft.maxStalling, { draft = draft.copy(maxStalling = it) },
        stringResource(R.string.settings_max_stalling),
        errorMessage = errors[SettingsField.MAX_STALLING],
    )
    AppTextField(
        draft.protectedTerms, { draft = draft.copy(protectedTerms = it) },
        stringResource(R.string.settings_protected_terms),
        singleLine = false,
    )
    AppSwitchRow(stringResource(R.string.settings_cloud_llm), live.cloudLlmEnabled, {
        scope.launch { toggleFailed = !store.change { s -> s.copy(cloudLlmEnabled = it) } }
    })
    Text(stringResource(R.string.settings_cloud_llm_hint), style = MaterialTheme.typography.bodySmall)
    SaveStatus(outcome, toggleFailed)
    AppButton(stringResource(R.string.settings_save), onClick = {
        scope.launch { outcome = store.commit { draft.applyTo(it) } }
    })
}

/** Gate_1, Gate_3 and the Night_Window (9.8, 9.9, 9.10). */
@Composable
fun GatesSettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val initial = rememberInitialSettings(store)
    SettingsPage(stringResource(R.string.settings_section_gates), onBack) {
        if (initial != null) GatesForm(store, initial)
    }
}

@Composable
private fun GatesForm(store: SettingsStore, initial: Settings) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(GatesDraft.from(initial)) }
    var outcome by remember { mutableStateOf<SaveOutcome?>(null) }
    val errors = (outcome as? SaveOutcome.Rejected)?.fieldErrors.orEmpty()

    @Composable
    fun gate(
        title: Int,
        value: Triple<String, String, String>,
        onChange: (Triple<String, String, String>) -> Unit,
        fields: Triple<SettingsField, SettingsField, SettingsField>,
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        AppTextField(
            value.first, { onChange(value.copy(first = it)) }, stringResource(R.string.settings_gate_number),
            errorMessage = errors[fields.first],
        )
        AppTextField(
            value.second, { onChange(value.copy(second = it)) }, stringResource(R.string.settings_gate_location),
            errorMessage = errors[fields.second],
        )
        AppTextField(
            value.third, { onChange(value.copy(third = it)) }, stringResource(R.string.settings_gate_landmark),
            errorMessage = errors[fields.third],
        )
    }

    gate(
        R.string.settings_gate1, draft.gate1, { draft = draft.copy(gate1 = it) },
        Triple(SettingsField.GATE1_NUMBER, SettingsField.GATE1_LOCATION, SettingsField.GATE1_LANDMARK),
    )
    gate(
        R.string.settings_gate3, draft.gate3, { draft = draft.copy(gate3 = it) },
        Triple(SettingsField.GATE3_NUMBER, SettingsField.GATE3_LOCATION, SettingsField.GATE3_LANDMARK),
    )
    Text(
        stringResource(R.string.settings_night_window),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    AppTextField(
        draft.nightStart, { draft = draft.copy(nightStart = it) }, stringResource(R.string.settings_night_start),
        errorMessage = errors[SettingsField.NIGHT_START],
    )
    AppTextField(
        draft.nightEnd, { draft = draft.copy(nightEnd = it) }, stringResource(R.string.settings_night_end),
        errorMessage = errors[SettingsField.NIGHT_END],
    )
    SaveStatus(outcome)
    AppButton(stringResource(R.string.settings_save), onClick = {
        scope.launch { outcome = store.commit { draft.applyTo(it) } }
    })
}

/** Disclosure, voice with sample playback, speaker and the quick replies editor (9.14, 9.15, 9.25, 13.3, 13.13). */
@Composable
fun CallSettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    val initial = rememberInitialSettings(store)
    SettingsPage(stringResource(R.string.settings_section_call), onBack) {
        if (initial != null) CallForm(store, initial)
    }
}

@Composable
private fun CallForm(store: SettingsStore, initial: Settings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val live by store.settings.collectAsState(initial = initial)
    var toggleFailed by remember { mutableStateOf(false) }
    var sampleFailed by remember { mutableStateOf(false) }
    val sampler = remember { VoiceSamplePlayer(context) }
    DisposableEffect(sampler) { onDispose { sampler.release() } }

    AppSwitchRow(stringResource(R.string.settings_disclosure), live.disclosureEnabled, {
        scope.launch { toggleFailed = !store.change { s -> s.copy(disclosureEnabled = it) } }
    })
    Text(stringResource(R.string.settings_disclosure_hint), style = MaterialTheme.typography.bodySmall)

    Text(
        stringResource(R.string.settings_voice),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    val female = stringResource(R.string.settings_voice_female)
    val male = stringResource(R.string.settings_voice_male)
    ChipRow(
        labels = listOf(female, male),
        selected = setOf(if (live.voiceGender == VoiceGender.FEMALE) female else male),
        onChipClick = { label ->
            val gender = if (label == female) VoiceGender.FEMALE else VoiceGender.MALE
            scope.launch { toggleFailed = !store.change { s -> s.copy(voiceGender = gender) } }
        },
    )
    Text(stringResource(R.string.settings_voice_hint), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
        listOf(
            Language.EN to R.string.settings_voice_sample_en,
            Language.HI to R.string.settings_voice_sample_hi,
        ).forEach { (language, label) ->
            AppButton(stringResource(label), primary = false, onClick = {
                scope.launch { sampleFailed = !sampler.play(language, live.voiceGender, displayAgentName(live.agentName)) }
            })
        }
    }
    if (sampleFailed) StatusBanner(stringResource(R.string.settings_voice_sample_failed), BannerSeverity.ERROR)

    AppSwitchRow(stringResource(R.string.settings_speaker, rememberAgentName()), live.speakerEnabled, {
        scope.launch { toggleFailed = !store.change { s -> s.copy(speakerEnabled = it) } }
    })
    SaveStatus(null, toggleFailed)

    QuickRepliesEditor(store, initial)
}

@Composable
private fun QuickRepliesEditor(store: SettingsStore, initial: Settings) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<QuickReply>().apply { addAll(initial.quickReplies) } }
    var outcome by remember { mutableStateOf<SaveOutcome?>(null) }
    val error = (outcome as? SaveOutcome.Rejected)?.fieldErrors?.get(SettingsField.QUICK_REPLIES)

    Text(
        stringResource(R.string.settings_quick_replies),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    rows.forEachIndexed { i, row ->
        AppCard(contentDescription = stringResource(R.string.settings_quick_replies)) {
            AppTextField(
                row.textEn, { rows[i] = row.copy(textEn = it) }, stringResource(R.string.settings_quick_reply_en),
                errorMessage = error,
            )
            AppTextField(row.textHi, { rows[i] = row.copy(textHi = it) }, stringResource(R.string.settings_quick_reply_hi))
            AppButton(stringResource(R.string.settings_quick_reply_delete), primary = false, onClick = { rows.removeAt(i) })
        }
    }
    AppButton(stringResource(R.string.settings_quick_reply_add), primary = false, onClick = { rows += QuickReply("", "") })
    SaveStatus(outcome)
    AppButton(stringResource(R.string.settings_save), onClick = {
        scope.launch { outcome = store.commit { QuickReplyDraft.applyTo(it, rows.toList()) } }
    })
}

/** Recording_Folder with the no-folder warning, the one-time auto-record note and the legal note (9.11, 9.12, 9.13). */
@Composable
fun RecordingSettingsScreen(model: SetupModel, actions: SetupActions, store: SettingsStore, onBack: () -> Unit) {
    val live by store.settings.collectAsState(initial = null)
    LaunchedEffect(Unit) { model.refresh() }
    SettingsPage(stringResource(R.string.settings_section_recording), onBack) {
        val uri = live?.recordingFolderUri
        val hasFolder = !uri.isNullOrBlank() && model.status.isGranted(SetupItem.RECORDING_FOLDER)
        if (hasFolder) {
            Text(stringResource(R.string.settings_folder_current, uri.orEmpty()), style = MaterialTheme.typography.bodyMedium)
        } else {
            StatusBanner(stringResource(R.string.settings_folder_none_warning), BannerSeverity.WARNING)
        }
        AppButton(
            stringResource(if (hasFolder) R.string.settings_folder_change else R.string.settings_folder_choose),
            onClick = { actions.request(SetupItem.RECORDING_FOLDER) },
        )
        if (!model.status.autoRecordAcknowledged) {
            StatusBanner(stringResource(R.string.settings_folder_autorecord))
            AppButton(
                stringResource(R.string.settings_folder_autorecord_ok),
                primary = false,
                onClick = { model.acknowledgeAutoRecord() },
            )
        }
        Text(stringResource(R.string.settings_folder_legal), style = MaterialTheme.typography.bodySmall)
    }
}

/** Silent_Send_Setting with the permission hint and the fixed TRAI destination (9.26, 14.30). */
@Composable
fun SpamSettingsScreen(model: SetupModel, actions: SetupActions, store: SettingsStore, onBack: () -> Unit) {
    val live by store.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.refresh() }
    SettingsPage(stringResource(R.string.settings_section_spam), onBack) {
        AppSwitchRow(
            stringResource(R.string.settings_spam_silent),
            live?.silentSendSpamReports == true,
            { scope.launch { failed = !store.change { s -> s.copy(silentSendSpamReports = it) } } },
            enabled = live != null,
        )
        Text(stringResource(R.string.settings_spam_silent_hint), style = MaterialTheme.typography.bodySmall)
        if (!model.status.isGranted(SetupItem.SMS)) {
            StatusBanner(stringResource(R.string.settings_spam_permission_missing), BannerSeverity.WARNING)
            AppButton(stringResource(R.string.action_request_again), primary = false, onClick = { actions.request(SetupItem.SMS) })
        }
        SaveStatus(null, failed)
        Text(stringResource(R.string.settings_spam_destination), style = MaterialTheme.typography.bodyLarge)
    }
}
