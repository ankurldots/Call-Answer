package com.callanswer.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.components.AppSwitchRow
import com.callanswer.app.ui.components.BannerSeverity
import com.callanswer.app.ui.components.StatusBanner
import com.callanswer.app.ui.theme.AppDimens

/**
 * "Silence the ringer for numbers not in my contacts" (Requirement 34), default off, stored on this device only.
 * Turning it on shows the purpose first and asks for `READ_CONTACTS` only when the User taps the allow button; the switch stays on if the User declines, but
 * nothing is silenced without the permission and a banner says so.
 */
@Composable
fun SilenceNonContactsRow() {
    val context = LocalContext.current
    val settings = (context.applicationContext as CallAnswerApp).localScreeningSettings
    fun granted() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    var enabled by remember { mutableStateOf(settings.silenceNonContacts) }
    var hasPermission by remember { mutableStateOf(granted()) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = granted()
        asked = true
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
        AppSwitchRow(stringResource(R.string.silence_non_contacts_enabled), enabled, {
            enabled = it
            settings.silenceNonContacts = it
        })
        Text(stringResource(R.string.silence_non_contacts_explanation), style = MaterialTheme.typography.bodySmall)
        if (enabled && !hasPermission) {
            Text(stringResource(R.string.silence_non_contacts_permission), style = MaterialTheme.typography.bodyMedium)
            if (asked) StatusBanner(stringResource(R.string.silence_non_contacts_permission_denied), BannerSeverity.WARNING)
            AppButton(stringResource(R.string.silence_non_contacts_allow), onClick = { launcher.launch(Manifest.permission.READ_CONTACTS) })
        }
    }
}
