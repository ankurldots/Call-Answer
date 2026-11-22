package com.callanswer.app.a11y

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import com.callanswer.app.CallLog
import com.callanswer.app.audio.CallAudioCaptureOwner
import com.callanswer.app.service.CallForegroundService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns [CallAudioA11yService] on and off through `Settings.Secure` (needs WRITE_SECURE_SETTINGS, granted once
 * over adb). It is on only while the app handles a call: UPI apps refuse to run while an accessibility service is
 * enabled. Other enabled services are never touched. Every change is logged with its reason.
 */
object A11yToggle {
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    private const val PREFS = "a11y_toggle"
    private const val KEY_LATENCY = "latency_ms"
    private const val KEY_HINT = "restricted_hint"
    const val BIND_TIMEOUT_MS = 5_000L
    const val CONNECT_WAIT_MS = 3_000L
    const val HARD_TIMEOUT_MS = 15 * 60 * 1000L
    const val GRANT_COMMAND = "adb shell pm grant com.callanswer.app android.permission.WRITE_SECURE_SETTINGS"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enableAt = 0L
    @Volatile var connected = false
        private set
    /** True between a successful [enable] and the next [disable]: this app switched the service on for a call. */
    @Volatile var enabledByApp = false
        private set
    @Volatile private var connectedSignal = CompletableDeferred<Unit>()
    private var hardTimeout: Runnable? = null
    private var bindWatch: Runnable? = null

    fun component(ctx: Context): String = ComponentName(ctx, CallAudioA11yService::class.java).flattenToString()

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun lastLatencyMs(ctx: Context): Long = prefs(ctx).getLong(KEY_LATENCY, -1)
    /** True after a bind timeout: Samsung "restricted settings" probably blocks the service. */
    fun restrictedHint(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HINT, false)

    private fun entries(ctx: Context): List<String> =
        A11yEnabledList.parse(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))

    fun isEnabled(ctx: Context): Boolean = A11yEnabledList.contains(entries(ctx), component(ctx))

    /** null when the call state cannot be read. */
    @android.annotation.SuppressLint("MissingPermission")
    fun callIsActive(ctx: Context): Boolean? = try {
        ctx.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE
    } catch (e: Throwable) {
        CallLog.w("a11y: callState unreadable", e); null
    }

    /** Switches the service on. Returns false when refused (no grant) or on error. */
    fun enable(ctx: Context, reason: String): Boolean {
        val app = ctx.applicationContext
        if (!hasPermission(app)) { CallLog.i("a11y: enable refused ($reason): WRITE_SECURE_SETTINGS not granted"); return false }
        return try {
            val list = A11yEnabledList.add(entries(app), component(app))
            connectedSignal = CompletableDeferred<Unit>().also { if (connected) it.complete(Unit) }
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, A11yEnabledList.join(list))
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            enableAt = SystemClock.elapsedRealtime()
            enabledByApp = true
            prefs(app).edit().putBoolean(KEY_HINT, false).apply()
            CallLog.i("a11y: ENABLED reason=\"$reason\" list=${A11yEnabledList.join(list)}")
            armTimers(app)
            true
        } catch (e: Throwable) {
            CallLog.w("a11y: enable failed ($reason)", e); false
        }
    }

    /** Switches only our service off. Safe to call at any time and more than once. */
    fun disable(ctx: Context, reason: String): Boolean {
        val app = ctx.applicationContext
        cancelTimers()
        enabledByApp = false
        if (!hasPermission(app)) { CallLog.i("a11y: disable skipped ($reason): WRITE_SECURE_SETTINGS not granted"); return false }
        return try {
            val before = entries(app)
            val rest = A11yEnabledList.remove(before, component(app))
            if (rest.size != before.size) {
                Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, A11yEnabledList.join(rest))
                Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED,
                    A11yEnabledList.accessibilityEnabledAfterRemoval(rest))
                CallLog.i("a11y: DISABLED reason=\"$reason\" remaining=${A11yEnabledList.join(rest).ifEmpty { "none" }}")
            } else {
                CallLog.i("a11y: disable ($reason): our service was not enabled, nothing changed")
            }
            true
        } catch (e: Throwable) {
            CallLog.w("a11y: disable failed ($reason)", e); false
        }
    }

    /** Waits until the service reports onServiceConnected; false after [timeoutMs]. */
    suspend fun awaitConnected(timeoutMs: Long): Boolean {
        if (connected) return true
        return withTimeoutOrNull(timeoutMs) { connectedSignal.await(); true } ?: false
    }

    /** Called from the service's onServiceConnected. */
    fun onConnected(ctx: Context) {
        connected = true
        cancelBindWatch()
        connectedSignal.complete(Unit)
        if (enableAt != 0L) {
            val ms = SystemClock.elapsedRealtime() - enableAt
            prefs(ctx).edit().putLong(KEY_LATENCY, ms).apply()
            CallLog.i("a11y: enable-to-onServiceConnected latency=$ms ms")
            enableAt = 0L
        } else {
            CallLog.i("a11y: onServiceConnected without a toggle from the app")
        }
    }

    fun onDisconnected() { connected = false }

    /** Safety net on app start and boot: switch our service off unless a call is active. */
    fun startupCheck(ctx: Context, source: String) {
        try {
            val enabled = isEnabled(ctx)
            if (!enabled) { CallLog.i("a11y: startup check ($source): our accessibility service is off"); return }
            val active = callIsActive(ctx)
            val running = CallForegroundService.running.value
            if (A11yPolicy.disableAtStartup(enabled, active, running)) disable(ctx, "startup check ($source), no call active")
            else CallLog.i("a11y: startup check ($source): enabled while a call is active, left on")
        } catch (e: Throwable) {
            CallLog.w("a11y: startup check failed", e)
        }
    }

    private fun armTimers(app: Context) {
        cancelTimers()
        bindWatch = Runnable {
            if (!connected) {
                prefs(app).edit().putBoolean(KEY_HINT, true).apply()
                CallLog.w("a11y: service NOT bound within ${BIND_TIMEOUT_MS / 1000} s after enabling via Settings.Secure. " +
                    "Likely restricted settings for a sideloaded app: App info > three dots > Allow restricted settings. Assisted mode.")
                CallAudioCaptureOwner.onA11yBindTimeout()
            }
        }.also { main.postDelayed(it, BIND_TIMEOUT_MS) }
        hardTimeout = Runnable { disable(app, "15-minute hard timeout") }.also { main.postDelayed(it, HARD_TIMEOUT_MS) }
    }

    private fun cancelBindWatch() { bindWatch?.let { main.removeCallbacks(it) }; bindWatch = null }
    private fun cancelTimers() {
        cancelBindWatch()
        hardTimeout?.let { main.removeCallbacks(it) }; hardTimeout = null
    }
}
