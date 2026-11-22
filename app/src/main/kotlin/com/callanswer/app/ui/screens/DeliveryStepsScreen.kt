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
fun DeliveryStepsScreen(state: CloudSettingsState, onOpenConflicts: () -> Unit, onBack: () -> Unit) {
    CloudSettingsPage(stringResource(R.string.settings_section_steps), state, onOpenConflicts, onBack) {
        DeliveryStepsForm(state, it)
    }
}

private sealed interface StepsStatus {
    data class Saved(val warnings: List<String>) : StepsStatus
    data class Invalid(val message: String) : StepsStatus
    data object NotYetApplied : StepsStatus
    data object NeedsChoice : StepsStatus
    data class ServerRejected(val field: String) : StepsStatus
}

@Composable
private fun DeliveryStepsForm(store: CloudSettingsState, initial: Settings) {
    val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<DeliveryStep>().apply { addAll(initial.deliverySteps) } }
    var status by remember { mutableStateOf<StepsStatus?>(null) }
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
        StepsStatus.NotYetApplied -> StatusBanner(stringResource(R.string.settings_not_applied), BannerSeverity.WARNING)
        StepsStatus.NeedsChoice -> StatusBanner(stringResource(R.string.settings_needs_choice), BannerSeverity.WARNING)
        is StepsStatus.ServerRejected ->
            StatusBanner(stringResource(R.string.settings_server_rejected, s.field), BannerSeverity.ERROR)
        null -> Unit
    }

    val missingFormat = stringResource(R.string.settings_step_missing_language)
    AppButton(stringResource(R.string.settings_save), onClick = {
        scope.launch {
            val base = store.settings ?: initial
            status = when (val r = DeliveryStepsDraft.applyTo(base, rows.toList())) {
                is DeliveryStepsDraft.Result.Invalid -> StepsStatus.Invalid(r.message)
                is DeliveryStepsDraft.Result.Ok -> {
                    val order = rows.map { it.id }
                    val warnings = r.warnings.map { w ->
                        String.format(missingFormat, order.indexOf(w.stepId) + 1, DeliveryStepsDraft.languageName(w.missing))
                    }
                    store.save(r.settings).toStepsStatus(warnings)
                }
            }
        }
    })
    AppButton(stringResource(R.string.settings_steps_reset), primary = false, onClick = {
        scope.launch {
            val result = store.save((store.settings ?: initial).copy(deliverySteps = DefaultDeliverySteps))
            if (result !is EditOutcome.Rejected) {
                rows.clear()
                rows.addAll(DefaultDeliverySteps)
            }
            status = result.toStepsStatus(emptyList())
        }
    })
}

private fun EditOutcome.toStepsStatus(warnings: List<String>): StepsStatus = when (this) {
    EditOutcome.Applied -> StepsStatus.Saved(warnings)
    EditOutcome.NotYetApplied -> StepsStatus.NotYetApplied
    EditOutcome.NeedsChoice -> StepsStatus.NeedsChoice
    is EditOutcome.Rejected -> StepsStatus.ServerRejected(field)
}
