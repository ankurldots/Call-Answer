package com.callanswer.app.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import com.callanswer.app.R

/**
 * One guided setup step, in onboarding order (design.md, "Permissions and role flow").
 * [permissions] lists runtime permissions; special-access items use their own check.
 */
enum class SetupItem(
    @StringRes val title: Int,
    @StringRes val purpose: Int,
    /** Feature that is disabled while the item is not granted (9.3). */
    @StringRes val feature: Int,
    /** Name shown to the User for the missing permission or access (9.3). */
    @StringRes val permissionLabel: Int,
    val permissions: List<String> = emptyList(),
) {
    SIGN_IN(
        R.string.setup_signin_title, R.string.setup_signin_purpose, R.string.feature_cloud_voice, R.string.perm_signin,
    ),
    CALL_SCREENING_ROLE(
        R.string.setup_role_title, R.string.setup_role_purpose, R.string.feature_screening, R.string.perm_role,
    ),
    CONTACTS(
        R.string.setup_contacts_title, R.string.setup_contacts_purpose, R.string.feature_contact_check,
        R.string.perm_contacts, listOf(Manifest.permission.READ_CONTACTS),
    ),
    PHONE(
        R.string.setup_phone_title, R.string.setup_phone_purpose, R.string.feature_answering,
        R.string.perm_phone,
        listOf(Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_PHONE_STATE),
    ),
    MICROPHONE(
        R.string.setup_mic_title, R.string.setup_mic_purpose, R.string.feature_listening,
        R.string.perm_mic, listOf(Manifest.permission.RECORD_AUDIO),
    ),
    NOTIFICATIONS(
        R.string.setup_notifications_title, R.string.setup_notifications_purpose, R.string.feature_notifications,
        R.string.perm_notifications,
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
    ),
    SMS(
        R.string.setup_sms_title, R.string.setup_sms_purpose, R.string.feature_sms,
        R.string.perm_sms, listOf(Manifest.permission.SEND_SMS),
    ),
    FULL_SCREEN_INTENT(
        R.string.setup_fsi_title, R.string.setup_fsi_purpose, R.string.feature_takeover_alert,
        R.string.perm_fsi,
    ),
    OVERLAY(
        R.string.setup_overlay_title, R.string.setup_overlay_purpose, R.string.feature_overlay,
        R.string.perm_overlay,
    ),
    BATTERY(
        R.string.setup_battery_title, R.string.setup_battery_purpose, R.string.feature_background,
        R.string.perm_battery,
    ),
    RECORDING_FOLDER(
        R.string.setup_folder_title, R.string.setup_folder_purpose, R.string.feature_recording_link,
        R.string.perm_folder,
    ),
    AUTO_RECORD(
        R.string.setup_autorecord_title, R.string.setup_autorecord_purpose, R.string.feature_autorecord,
        R.string.perm_autorecord,
    ),
}

/** Snapshot of which items are granted. Re-read on every resume, like the spike's status list. */
data class SetupStatus(
    val granted: Map<SetupItem, Boolean>,
    val autoRecordAcknowledged: Boolean,
) {
    fun isGranted(item: SetupItem) = granted[item] == true

    /** Items the User has not granted. The auto-record instruction is not a permission and never listed. */
    val denied: List<SetupItem>
        get() = SetupItem.entries.filter { it != SetupItem.AUTO_RECORD && !isGranted(it) }

    companion object {
        fun read(
            context: Context,
            folderGranted: Boolean,
            autoRecordAcknowledged: Boolean,
            signedIn: Boolean = false,
        ): SetupStatus {
            val granted = SetupItem.entries.associateWith { item ->
                when (item) {
                    SetupItem.SIGN_IN -> signedIn
                    SetupItem.CALL_SCREENING_ROLE -> isRoleHeld(context)
                    SetupItem.FULL_SCREEN_INTENT -> canUseFullScreenIntent(context)
                    SetupItem.OVERLAY -> android.provider.Settings.canDrawOverlays(context)
                    SetupItem.BATTERY -> isBatteryUnrestricted(context)
                    SetupItem.RECORDING_FOLDER -> folderGranted
                    SetupItem.AUTO_RECORD -> autoRecordAcknowledged
                    else -> item.permissions.all {
                        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                    }
                }
            }
            return SetupStatus(granted, autoRecordAcknowledged)
        }

        fun isRoleHeld(context: Context): Boolean {
            val rm = context.getSystemService(RoleManager::class.java) ?: return false
            return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        }


        fun canUseFullScreenIntent(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 34) {
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            } else {
                true
            }

        fun isBatteryUnrestricted(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
}
