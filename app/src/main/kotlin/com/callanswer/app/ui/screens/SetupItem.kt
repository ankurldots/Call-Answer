package com.callanswer.app.ui.screens
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import com.callanswer.app.R
/**
 * One device permission the Agent's alerts need. Sign-in, consent, number and User_Mobile are onboarding
 * steps (see [OnboardingStep]). The companion app needs only the notification permissions for the take-over alert; it answers no calls and needs no call, microphone or SMS access.
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
    NOTIFICATIONS(
        R.string.setup_notifications_title, R.string.setup_notifications_purpose, R.string.feature_notifications,
        R.string.perm_notifications,
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList(),
    ),
    FULL_SCREEN_INTENT(
        R.string.setup_fsi_title, R.string.setup_fsi_purpose, R.string.feature_takeover_alert,
        R.string.perm_fsi,
    ),
}
/** Snapshot of which items are granted. Re-read on every resume. */
data class SetupStatus(val granted: Map<SetupItem, Boolean>) {
    fun isGranted(item: SetupItem) = granted[item] == true
    /** Items the User has not granted. */
    val denied: List<SetupItem>
        get() = SetupItem.entries.filter { !isGranted(it) }
    companion object {
        fun read(context: Context): SetupStatus {
            val granted = SetupItem.entries.associateWith { item ->
                when (item) {
                    SetupItem.FULL_SCREEN_INTENT -> canUseFullScreenIntent(context)
                    else -> item.permissions.all {
                        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
                    }
                }
            }
            return SetupStatus(granted)
        }
        fun canUseFullScreenIntent(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= 34) {
                context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            } else {
                true
            }
    }
}
