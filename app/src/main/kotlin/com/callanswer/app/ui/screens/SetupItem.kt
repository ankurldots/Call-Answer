package com.callanswer.app.ui.screens

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
    CALL_PHONE(
        R.string.setup_call_phone_title, R.string.setup_call_phone_purpose, R.string.feature_transfer,
        R.string.perm_call_phone, listOf(Manifest.permission.CALL_PHONE),
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
    ACCESSIBILITY(
        R.string.setup_accessibility_title, R.string.setup_accessibility_purpose, R.string.feature_call_audio,
        R.string.perm_accessibility,
    ),
    FULL_SCREEN_INTENT(
        R.string.setup_fsi_title, R.string.setup_fsi_purpose, R.string.feature_takeover_alert,
        R.string.perm_fsi,
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
        fun read(context: Context, folderGranted: Boolean, autoRecordAcknowledged: Boolean): SetupStatus {
            val granted = SetupItem.entries.associateWith { item ->
                when (item) {
                    SetupItem.CALL_SCREENING_ROLE -> isRoleHeld(context)
                    SetupItem.ACCESSIBILITY -> isAccessibilityEnabled(context)
                    SetupItem.FULL_SCREEN_INTENT -> canUseFullScreenIntent(context)
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

        fun isAccessibilityEnabled(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.contains("${context.packageName}/") == true

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
