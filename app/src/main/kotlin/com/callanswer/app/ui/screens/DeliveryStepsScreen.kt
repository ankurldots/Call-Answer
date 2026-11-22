package com.callanswer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.callanswer.app.data.SettingsStore
import com.callanswer.app.data.UpdateResult
import com.callanswer.app.tts.VoiceSamplePlayer
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.AppTextField
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import com.callanswer.core.model.DefaultDeliverySteps
import com.callanswer.core.model.DeliveryStep
import com.callanswer.core.model.Language
import com.callanswer.core.model.Settings
import kotlinx.coroutines.launch
import java.util.UUID

/** Delivery_Steps editor: add, edit, delete, reorder, reset, and full-directions sample (9.16 to 9.22). */
@Composable
fun DeliveryStepsScreen(store: SettingsStore, onBack: () -> Unit) {
    val initial = rememberInitialSettings(store)
    SettingsPage(stringResource(R.string.settings_section_steps), onBack) {
        if (initial != null) DeliveryStepsForm(store, initial)
    }
}

private sealed interface StepsStatus {
    data class Saved(val warnings: List<String>) : StepsStatus
    data class Invalid(val message: String) : StepsStatus
    data object StoreRejected : StepsStatus
}

@Composable
private fun DeliveryStepsForm(store: SettingsStore, initial: Settings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val live by store.settings.collectAsState(initial = initial)
    val rows = remember { mutableStateListOf<DeliveryStep>().apply { addAll(initial.deliverySteps) } }
    var status by remember { mutableStateOf<StepsStatus?>(null) }
    var sampleFailure by remember { mutableStateOf(VoiceSamplePlayer.Failure.NONE) }
    var samplePlaying by remember { mutableStateOf(false) }
    val sampler = remember { VoiceSamplePlayer(context) }
    DisposableEffect(sampler) { onDispose { sampler.release() } }

    Text(stringResource(R.string.settings_steps_hint), style = MaterialTheme.typography.bodyMedium)
    rows.forEachIndexed { i, row ->
        val title = stringResource(R.string.settings_step_title, i + 1)
        AppCard(contentDescription = title) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            AppTextField(
                row.textEn, { rows[i] = row.copy(textEn = it) }, stringResource(R.string.settings_quick_reply_en),
                singleLine = false,
            )
            AppTextField(
                row.textHi, { rows[i] = row.copy(textHi = it) }, stringResource(R.string.settings_quick_reply_hi),
                singleLine = false,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
                AppButton(
                    stringResource(R.string.settings_step_up), primary = false, enabled = i > 0,
                    contentDescription = stringResource(R.string.settings_step_up_cd, i + 1),
                    onClick = { DeliveryStepsDraft.move(rows.toList(), i, -1).let { rows.clear(); rows.addAll(it) } },
                )
                AppButton(
                    stringResource(R.string.settings_step_down), primary = false, enabled = i < rows.lastIndex,
                    contentDescription = stringResource(R.string.settings_step_down_cd, i + 1),
                    onClick = { DeliveryStepsDraft.move(rows.toList(), i, 1).let { rows.clear(); rows.addAll(it) } },
                )
                AppButton(
                    stringResource(R.string.settings_quick_reply_delete), primary = false,
                    contentDescription = stringResource(R.string.settings_step_delete_cd, i + 1),
                    onClick = { rows.removeAt(i) },
                )
            }
        }
    }
    AppButton(stringResource(R.string.settings_step_add), primary = false, onClick = {
        rows += DeliveryStepsDraft.newStep("step-${UUID.randomUUID()}")
    })

    when (val s = status) {
        is StepsStatus.Saved -> {
            StatusBanner(stringResource(R.string.settings_saved))
            s.warnings.forEach { StatusBanner(it, BannerSeverity.WARNING) }
        }
        is StepsStatus.Invalid -> StatusBanner(s.message, BannerSeverity.ERROR)
        StepsStatus.StoreRejected -> StatusBanner(stringResource(R.string.settings_save_failed), BannerSeverity.ERROR)
        null -> Unit
    }

    val missingFormat = stringResource(R.string.settings_step_missing_language)
    AppButton(stringResource(R.string.settings_save), onClick = {
        scope.launch {
            val base = store.current()
            status = when (val r = DeliveryStepsDraft.applyTo(base, rows.toList())) {
                is DeliveryStepsDraft.Result.Invalid -> StepsStatus.Invalid(r.message)
                is DeliveryStepsDraft.Result.Ok -> when (store.update(r.settings)) {
                    UpdateResult.Saved -> {
                        val order = rows.map { it.id }
                        StepsStatus.Saved(
                            r.warnings.map { w ->
                                String.format(
                                    missingFormat,
                                    order.indexOf(w.stepId) + 1,
                                    DeliveryStepsDraft.languageName(w.missing),
                                )
                            },
                        )
                    }
                    is UpdateResult.Rejected -> StepsStatus.StoreRejected
                }
            }
        }
    })
    AppButton(stringResource(R.string.settings_steps_reset), primary = false, onClick = {
        scope.launch {
            val result = store.update(store.current().copy(deliverySteps = DefaultDeliverySteps))
            if (result == UpdateResult.Saved) {
                rows.clear()
                rows.addAll(DefaultDeliverySteps)
                status = StepsStatus.Saved(emptyList())
            } else {
                status = StepsStatus.StoreRejected
            }
        }
    })

    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm), modifier = Modifier.fillMaxWidth()) {
        listOf(
            Language.EN to R.string.settings_steps_sample_en,
            Language.HI to R.string.settings_steps_sample_hi,
        ).forEach { (language, label) ->
            AppButton(stringResource(label), primary = false, enabled = !samplePlaying, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    val text = DeliveryStepsDraft.sampleText(rows.toList(), language, live.mapLocation)
                    Log.i("CallAnswer", "Directions playback requested lang=$language gender=${live.voiceGender} textLength=${text.length}")
                    sampleFailure = VoiceSamplePlayer.Failure.NONE
                    if (text.isBlank()) {
                        Log.w("CallAnswer", "Directions playback fail: no text for $language")
                        sampleFailure = VoiceSamplePlayer.Failure.NO_VOICE
                        return@launch
                    }
                    samplePlaying = true
                    try {
                        val ok = sampler.speak(text, language, live.voiceGender)
                        sampleFailure = if (ok) VoiceSamplePlayer.Failure.NONE else sampler.lastFailure
                        Log.i("CallAnswer", "Directions playback ${if (ok) "done" else "fail"} lang=$language failure=${sampler.lastFailure}")
                    } finally {
                        samplePlaying = false
                    }
                }
            })
        }
    }
    Text(stringResource(R.string.settings_steps_sample_hint), style = MaterialTheme.typography.bodySmall)
    if (samplePlaying) StatusBanner(stringResource(R.string.settings_voice_sample_playing), BannerSeverity.INFO)
    if (sampleFailure != VoiceSamplePlayer.Failure.NONE) {
        StatusBanner(
            stringResource(
                when (sampleFailure) {
                    VoiceSamplePlayer.Failure.HINDI_VOICE_DOWNLOADING -> R.string.settings_voice_sample_hindi_downloading
                    VoiceSamplePlayer.Failure.PLAYBACK -> R.string.settings_voice_sample_playback_failed
                    else -> R.string.settings_voice_sample_failed
                },
            ),
            BannerSeverity.ERROR,
        )
    }
}
