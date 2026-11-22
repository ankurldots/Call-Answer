package com.callanswer.spike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast

/**
 * S2c: turns our own accessibility service on and off through Settings.Secure (needs WRITE_SECURE_SETTINGS,
 * granted once over adb). Other enabled services are preserved. Every change is logged with its reason.
 */
object A11yToggle {
    private const val TAG = "S2c"
    private const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val HARD_TIMEOUT_MS = 10 * 60 * 1000L
    private const val PREFS = "spike_s2c"
    private const val KEY_LATENCY = "latency_ms"
    private const val KEY_HINT = "restricted_hint"

    const val GRANT_COMMAND = "adb shell pm grant com.callanswer.spike android.permission.WRITE_SECURE_SETTINGS"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var enableAt = 0L
    @Volatile var connected = false
        private set
    private var hardTimeout: Runnable? = null
    private var bindWatch: Runnable? = null

    fun component(ctx: Context) = ComponentName(ctx, SpikeCallAudioA11yService::class.java).flattenToString()

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun lastLatencyMs(ctx: Context): Long = prefs(ctx).getLong(KEY_LATENCY, -1)
    fun restrictedHint(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HINT, false)

    private fun entries(ctx: Context): List<String> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }

    /** True when our component is in the enabled list (same component compared in short or flattened form). */
    fun isEnabled(ctx: Context): Boolean = entries(ctx).any { matches(ctx, it) }

    private fun matches(ctx: Context, entry: String) =
        ComponentName.unflattenFromString(entry) == ComponentName(ctx, SpikeCallAudioA11yService::class.java)

    fun callIsActive(ctx: Context): Boolean = try {
        ctx.getSystemService(TelephonyManager::class.java).callState != TelephonyManager.CALL_STATE_IDLE
    } catch (e: Throwable) { SpikeLog.log(TAG, "callState unreadable: $e"); false }

    fun enable(ctx: Context, reason: String): Boolean {
        val app = ctx.applicationContext
        if (!hasPermission(app)) { SpikeLog.log(TAG, "enable refused ($reason): WRITE_SECURE_SETTINGS not granted"); return false }
        return try {
            val others = entries(app)
            val list = if (others.any { matches(app, it) }) others else others + component(app)
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            enableAt = SystemClock.elapsedRealtime()
            prefs(app).edit().putBoolean(KEY_HINT, false).apply()
            SpikeLog.log(TAG, "accessibility ENABLED reason=\"$reason\" list=${list.joinToString(":")}")
            armTimers(app)
            true
        } catch (e: Throwable) {
            SpikeLog.log(TAG, "enable failed ($reason): $e"); false
        }
    }

    fun disable(ctx: Context, reason: String): Boolean {
        val app = ctx.applicationContext
        cancelTimers()
        if (!hasPermission(app)) { SpikeLog.log(TAG, "disable skipped ($reason): WRITE_SECURE_SETTINGS not granted"); return false }
        return try {
            val before = entries(app)
            val rest = before.filterNot { matches(app, it) }
            if (rest.size != before.size) {
                Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.joinToString(":"))
                if (rest.isEmpty()) Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
                SpikeLog.log(TAG, "accessibility DISABLED reason=\"$reason\" remaining=${rest.joinToString(":").ifEmpty { "none" }} " +
                    "accessibility_enabled=${if (rest.isEmpty()) 0 else 1}")
            } else {
                SpikeLog.log(TAG, "disable ($reason): our service was not enabled, nothing changed")
            }
            true
        } catch (e: Throwable) {
            SpikeLog.log(TAG, "disable failed ($reason): $e"); false
        }
    }

    /** Called from the service's onServiceConnected. */
    fun onConnected(ctx: Context) {
        connected = true
        cancelBindWatch()
        if (enableAt != 0L) {
            val ms = SystemClock.elapsedRealtime() - enableAt
            prefs(ctx).edit().putLong(KEY_LATENCY, ms).apply()
            SpikeLog.log(TAG, "enable-to-onServiceConnected latency=$ms ms")
            enableAt = 0L
        } else {
            SpikeLog.log(TAG, "onServiceConnected without a toggle from the app (enabled by hand?)")
        }
    }

    fun onDisconnected() { connected = false }

    /** Safety net on app start and boot: switch our service off unless a call is active. */
    fun startupCheck(ctx: Context, source: String) {
        SpikeLog.init(ctx)
        if (!isEnabled(ctx)) { SpikeLog.log(TAG, "startup check ($source): our accessibility service is off"); return }
        if (callIsActive(ctx)) { SpikeLog.log(TAG, "startup check ($source): enabled but a call is active, left on"); return }
        disable(ctx, "startup check ($source), no call active")
    }

    private fun armTimers(app: Context) {
        cancelTimers()
        bindWatch = Runnable {
            if (!connected) {
                prefs(app).edit().putBoolean(KEY_HINT, true).apply()
                SpikeLog.log(TAG, "service NOT bound within ${BIND_TIMEOUT_MS / 1000} s after enabling via Settings.Secure. " +
                    "Likely Samsung restricted settings for a sideloaded app: App info > three dots > Allow restricted settings, then retry.")
                Toast.makeText(app, "Accessibility did not bind. Open App info > menu > Allow restricted settings.", Toast.LENGTH_LONG).show()
            }
        }.also { main.postDelayed(it, BIND_TIMEOUT_MS) }
        hardTimeout = Runnable { disable(app, "10-minute hard timeout") }.also { main.postDelayed(it, HARD_TIMEOUT_MS) }
    }

    private fun cancelBindWatch() { bindWatch?.let { main.removeCallbacks(it) }; bindWatch = null }
    private fun cancelTimers() {
        cancelBindWatch()
        hardTimeout?.let { main.removeCallbacks(it) }; hardTimeout = null
    }

    fun appInfoIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
}
