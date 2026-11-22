package com.callanswer.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.callanswer.app.R
import kotlinx.coroutines.launch

/** Starts the request for one [SetupItem] or opens its system settings page. */
class SetupActions(
    private val context: Context,
    private val model: SetupModel,
    private val launchPermissions: (Array<String>) -> Unit,
    private val setPending: (SetupItem) -> Unit,
) {
    /** "Request again" on Home and the primary button in onboarding. */
    fun request(item: SetupItem) {
        setPending(item)
        when (item) {
            SetupItem.FULL_SCREEN_INTENT -> openFullScreenIntentSettings()
            else -> {
                val missing = item.permissions.filter {
                    context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                if (missing.isNotEmpty()) launchPermissions(missing.toTypedArray())
            }
        }
    }

    /** "Open settings": the page where the User can grant the item by hand. */
    fun openSettings(item: SetupItem) {
        when (item) {
            SetupItem.FULL_SCREEN_INTENT -> openFullScreenIntentSettings()
            else -> openAppInfo()
        }
    }

    /** App info page, where the User can grant a runtime permission by hand. */
    fun openAppInfo() = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            start(
                Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.setup_settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun rememberSetupActions(model: SetupModel): SetupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { arrayOfNulls<SetupItem>(1) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val item = pending[0]
        // A denial is shown at once (9.3); the full re-read happens in refresh().
        if (item != null && result.values.any { !it }) model.recordDenied(item)
        scope.launch { model.refresh() }
    }
    return remember(context, model) {
        SetupActions(
            context = context,
            model = model,
            launchPermissions = { permissionLauncher.launch(it) },
            setPending = { pending[0] = it },
        )
    }
}
