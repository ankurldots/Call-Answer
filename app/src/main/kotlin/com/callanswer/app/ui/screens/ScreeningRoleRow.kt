package com.callanswer.app.ui.screens

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
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
import com.callanswer.app.R
import com.callanswer.app.ui.components.AppButton
import com.callanswer.app.ui.theme.AppDimens

/** Whether the app holds the call screening role (false when the role does not exist on this device). */
fun hasScreeningRole(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val rm = context.getSystemService(RoleManager::class.java) ?: return false
    return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
}

/**
 * Call screening role row (Requirement 33). The Ringing_Alert needs the role so Android hands over the incoming
 * number; the app only reads the number and never blocks, silences or answers a call.
 */
@Composable
fun ScreeningRoleRow() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val context = LocalContext.current
    var held by remember { mutableStateOf(hasScreeningRole(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        held = hasScreeningRole(context)
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppDimens.spaceSm)) {
        Text(stringResource(R.string.screening_role_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.screening_role_explanation), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(if (held) R.string.screening_role_granted else R.string.screening_role_missing),
            style = MaterialTheme.typography.bodyMedium,
        )
        val rm = context.getSystemService(RoleManager::class.java)
        if (!held && rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            AppButton(stringResource(R.string.screening_role_request), onClick = {
                launcher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
            })
        }
    }
}
