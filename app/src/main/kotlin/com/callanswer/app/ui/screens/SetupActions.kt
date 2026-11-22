package com.callanswer.app.ui.screens

import android.app.Activity
import android.app.role.RoleManager
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
    private val launchRole: (Intent) -> Unit,
    private val launchFolderPicker: () -> Unit,
    private val setPending: (SetupItem) -> Unit,
) {
    /** "Request again" on Home and the primary button in onboarding. */
    fun request(item: SetupItem) {
        setPending(item)
        when (item) {
            SetupItem.CALL_SCREENING_ROLE -> requestRole(item)
            SetupItem.ACCESSIBILITY -> start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupItem.FULL_SCREEN_INTENT -> openFullScreenIntentSettings()
            SetupItem.BATTERY -> start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            SetupItem.RECORDING_FOLDER -> launchFolderPicker()
            SetupItem.AUTO_RECORD -> model.acknowledgeAutoRecord()
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
            SetupItem.CALL_SCREENING_ROLE -> start(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            SetupItem.ACCESSIBILITY -> start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            SetupItem.FULL_SCREEN_INTENT -> openFullScreenIntentSettings()
            SetupItem.BATTERY -> start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            else -> openAppInfo()
        }
    }

    /** App info page; also where "Allow restricted settings" lives for sideloaded apps (Android 13+). */
    fun openAppInfo() = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )

    private fun requestRole(item: SetupItem) {
        val rm = context.getSystemService(RoleManager::class.java)
        when {
            !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) -> {
                Toast.makeText(context, R.string.setup_role_unavailable, Toast.LENGTH_LONG).show()
                model.recordDenied(item)
            }
            rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) -> Unit
            else -> launchRole(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
        }
    }

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
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending[0]?.takeIf { result.resultCode != Activity.RESULT_OK }?.let(model::recordDenied)
        scope.launch { model.refresh() }
    }
    val folderLauncher = rememberLauncherForActivityResult(model.folderContract()) { uri ->
        scope.launch {
            val saved = uri != null && model.saveFolder(uri)
            if (!saved) pending[0]?.let(model::recordDenied)
        }
    }
    return remember(context, model) {
        SetupActions(
            context = context,
            model = model,
            launchPermissions = { permissionLauncher.launch(it) },
            launchRole = { roleLauncher.launch(it) },
            launchFolderPicker = { folderLauncher.launch(null) },
            setPending = { pending[0] = it },
        )
    }
}
