package com.callanswer.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.callanswer.app.CallAnswerApp
import com.callanswer.app.R
import com.callanswer.app.sms.SimOption
import com.callanswer.app.sms.activeSubscriptions
import com.callanswer.app.sms.isMultiSimDevice
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner

/**
 * SIM for the map text (Requirement 10.15). The choice is stored on this device only. A phone that can hold two SIMs shows
 * a button; only that button asks for `READ_PHONE_STATE`, to list the active SIMs. With one active SIM there is nothing to pick.
 */
@Composable
fun SmsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val local = (context.applicationContext as CallAnswerApp).localSmsSettings
    val multiSim = remember { isMultiSimDevice(context) }
    var sims by remember { mutableStateOf(activeSubscriptions(context)) }
    var denied by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(local.selectedSubscriptionId) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        sims = activeSubscriptions(context)
    }
    SettingsPage(stringResource(R.string.settings_section_sms), onBack) {
        Text(stringResource(R.string.settings_sms_intro), style = MaterialTheme.typography.bodyLarge)
        val list: List<SimOption>? = sims
        when {
            !multiSim -> Text(stringResource(R.string.settings_sms_single_sim), style = MaterialTheme.typography.bodyMedium)
            list == null -> {
                Text(stringResource(R.string.settings_sms_sim_permission), style = MaterialTheme.typography.bodyMedium)
                if (denied) StatusBanner(stringResource(R.string.settings_sms_sim_permission_denied), BannerSeverity.WARNING)
                AppButton(stringResource(R.string.settings_sms_choose_sim), onClick = { launcher.launch(Manifest.permission.READ_PHONE_STATE) })
            }
            list.size < 2 -> Text(stringResource(R.string.settings_sms_sim_only_one), style = MaterialTheme.typography.bodyMedium)
            else -> {
                val current = selected?.takeIf { id -> list.any { it.id == id } }
                AppButton(
                    stringResource(R.string.settings_sms_default_sim),
                    primary = current == null,
                    onClick = { local.selectedSubscriptionId = null; selected = null },
                )
                list.forEach { sim ->
                    AppButton(
                        sim.label,
                        primary = current == sim.id,
                        onClick = { local.selectedSubscriptionId = sim.id; selected = sim.id },
                    )
                }
            }
        }
    }
}
