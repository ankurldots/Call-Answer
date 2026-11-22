package com.callanswer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.callanswer.app.R
import com.callanswer.app.api.ApiException
import com.callanswer.app.api.CompanionApi
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.AppDialog
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Privacy_Consent screen (Requirement 17 criteria 1 to 4). States plainly that call audio is processed outside India
 * (AWS Tokyo), that an automated assistant answers, that calls may be recorded, and the retention period. The User
 * accepts or withdraws here. [onAccepted] runs after a successful acceptance (onboarding continues from there).
 */
@Composable
fun ConsentScreen(api: CompanionApi, onBack: (() -> Unit)?, onAccepted: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ConsentStatus>(ConsentStatus.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var withdrawn by remember { mutableStateOf(false) }
    var confirmWithdraw by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        status = ConsentStatus.Loading
        status = try {
            if (api.me().consentAccepted) ConsentStatus.Accepted else ConsentStatus.NotAccepted
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            ConsentStatus.Failed(consentErrorText(e))
        }
    }

    fun call(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = consentErrorText(e)
            } finally {
                busy = false
            }
        }
    }

    SettingsPage(stringResource(R.string.consent_title), onBack) {
        Text(stringResource(R.string.consent_intro), style = MaterialTheme.typography.bodyLarge)
        AppCard(stringResource(R.string.consent_title)) {
            Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
                Text(stringResource(R.string.consent_point_region), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.consent_point_assistant), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.consent_point_recording), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.consent_point_retention, CONSENT_DEFAULT_RETENTION_DAYS),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.consent_point_no_other), style = MaterialTheme.typography.bodyMedium)
            }
        }
        error?.let { StatusBanner(stringResource(it), BannerSeverity.ERROR) }
        when (val s = status) {
            ConsentStatus.Loading -> Text(stringResource(R.string.consent_loading))
            is ConsentStatus.Failed -> {
                StatusBanner(stringResource(s.messageRes), BannerSeverity.ERROR)
                AppButton(stringResource(R.string.consent_retry), primary = false, onClick = { reload++ })
            }
            ConsentStatus.NotAccepted -> {
                StatusBanner(
                    stringResource(if (withdrawn) R.string.consent_withdrawn_status else R.string.consent_not_accepted_status),
                    BannerSeverity.WARNING,
                )
                AppButton(stringResource(R.string.consent_accept), enabled = !busy, onClick = {
                    call {
                        api.acceptConsent(CONSENT_TEXT_VERSION)
                        status = ConsentStatus.Accepted
                        withdrawn = false
                        onAccepted()
                    }
                })
            }
            ConsentStatus.Accepted -> {
                StatusBanner(stringResource(R.string.consent_accepted_status))
                AppButton(stringResource(R.string.consent_withdraw), primary = false, enabled = !busy, onClick = { confirmWithdraw = true })
            }
        }
    }

    if (confirmWithdraw) {
        AppDialog(
            title = stringResource(R.string.consent_withdraw_title),
            onConfirm = {
                confirmWithdraw = false
                call { api.withdrawConsent(); status = ConsentStatus.NotAccepted; withdrawn = true }
            },
            onDismiss = { confirmWithdraw = false },
            confirmText = stringResource(R.string.consent_withdraw),
        ) { Text(stringResource(R.string.consent_withdraw_body)) }
    }
}
