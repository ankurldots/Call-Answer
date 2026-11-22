package com.callanswer.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.R
import com.callanswer.app.data.SyncResult
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppCard
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens
import kotlinx.coroutines.launch

/**
 * When the server holds no settings document (Settings_Version 0) and this phone has settings of its own, offers to
 * upload them as the first document with `expectedVersion` 0 (Requirement 31 criterion 5, Requirement 26 criterion 8).
 * [state] must be the shared settings state of the screen, so a finished upload shows at once.
 */
@Composable
fun SettingsUploadOffer(state: CloudSettingsState) {
    val app = LocalContext.current.applicationContext as CallAnswerApp
    val scope = rememberCoroutineScope()
    var offered by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<EditOutcome?>(null) }
    // Re-checked after each sync of the screen, because the first read may fail offline.
    LaunchedEffect(state.loaded, state.settings) {
        if (!state.loaded) return@LaunchedEffect
        val local = app.settingsStore.current()
        offered = state.repo.pendingEdit() == null && shouldOfferUpload(state.repo.cached()?.version, local)
    }
    if (!offered || dismissed) return
    AppCard(stringResource(R.string.upload_offer_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
            StatusBanner(stringResource(R.string.upload_offer_body))
            when (val f = failed) {
                is EditOutcome.Rejected -> StatusBanner(stringResource(R.string.settings_server_rejected, f.field), BannerSeverity.ERROR)
                null -> Unit
                else -> StatusBanner(stringResource(R.string.upload_offer_failed), BannerSeverity.ERROR)
            }
            AppButton(stringResource(R.string.upload_offer_accept), enabled = !busy, onClick = {
                busy = true; failed = null
                scope.launch {
                    val result = state.repo.uploadInitial(initialUploadDocument(app.settingsStore.current()))
                    if (result is SyncResult.Saved || result is SyncResult.UpToDate) {
                        state.refresh()
                        dismissed = true
                    } else {
                        failed = result.toOutcome()
                    }
                    busy = false
                }
            })
            AppButton(stringResource(R.string.upload_offer_dismiss), primary = false, onClick = { dismissed = true })
        }
    }
}
