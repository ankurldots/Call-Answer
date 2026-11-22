package com.callanswer.app.legacy
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
/**
 * Safety check for devices that ran the archived on-device build. That build switched its accessibility service
 * on through `Settings.Secure`; this build never does, and never ships the service. If an old entry is still in
 * the enabled list (a crash or reboot left it on), remove it. Other services are never touched. The write needs
 * WRITE_SECURE_SETTINGS, which was granted once over adb on those devices; without it this does nothing.
 */
object LegacyA11yCleanup {
    private const val TAG = "CallAnswer"
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    /** Component of the archived accessibility service (the class no longer exists in this build). */
    const val LEGACY_COMPONENT = "com.callanswer.app/com.callanswer.app.a11y.CallAudioA11yService"
    /** Called at app start and after boot. Never throws. */
    fun run(context: Context, source: String) {
        try {
            val app = context.applicationContext
            val before = A11yEnabledList.parse(
                Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            )
            if (!A11yEnabledList.contains(before, LEGACY_COMPONENT)) return
            if (app.checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "legacy a11y cleanup ($source): old service entry present, WRITE_SECURE_SETTINGS not granted")
                return
            }
            val rest = A11yEnabledList.remove(before, LEGACY_COMPONENT)
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, A11yEnabledList.join(rest))
            Settings.Secure.putInt(
                app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, A11yEnabledList.accessibilityEnabledAfterRemoval(rest),
            )
            Log.i(TAG, "legacy a11y cleanup ($source): removed old service entry")
        } catch (e: Throwable) {
            Log.w(TAG, "legacy a11y cleanup ($source) failed", e)
        }
    }
}
