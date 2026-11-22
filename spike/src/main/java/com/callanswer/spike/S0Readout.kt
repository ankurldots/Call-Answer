package com.callanswer.spike

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager

/** S0: reads device facts the design assumes. Every probe is guarded, failures are reported as text. */
object S0Readout {

    private val RECORDING_CANDIDATE_PACKAGES = listOf(
        "com.samsung.android.dialer",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.incallui",
    )

    fun collect(context: Context): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        out += "Model" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"
        out += "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        out += "One UI" to oneUi()
        out += "Security patch" to (Build.VERSION.SECURITY_PATCH ?: "unknown")
        out += "Build" to Build.DISPLAY
        out += "RAM" to ram(context)
        out += "Carrier" to carrier(context)
        out += "VoLTE" to volte(context)
        out += "Default dialer" to defaultDialer(context)
        out += "Samsung recording settings" to recordingSettings(context)
        return out
    }

    fun logAll(context: Context): List<Pair<String, String>> {
        val rows = collect(context)
        rows.forEach { (k, v) -> SpikeLog.log("S0", "$k: $v") }
        return rows
    }

    private fun sysProp(key: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }

    /** ro.build.version.oneui is e.g. 60101 for One UI 6.1.1. Falls back to SEM_PLATFORM_INT. */
    private fun oneUi(): String {
        val prop = sysProp("ro.build.version.oneui")?.toIntOrNull()
        if (prop != null) {
            return "${prop / 10000}.${(prop / 100) % 100}.${prop % 100} (ro.build.version.oneui=$prop)"
        }
        val sem = try {
            Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null)
        } catch (e: Throwable) {
            null
        }
        return if (sem != null) {
            // SEM_PLATFORM_INT: 90000 + 100*major-ish scheme; One UI x.y = (sem - 90000) / 10000 . ((sem - 90000) / 100) % 100
            val v = sem - 90000
            "${v / 10000}.${(v / 100) % 100} (SEM_PLATFORM_INT=$sem)"
        } else {
            "not found (non-Samsung or hidden)"
        }
    }

    private fun ram(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
        return "%.2f GB total (%d MB available now)".format(gb, mi.availMem / (1024 * 1024))
    }

    private fun carrier(context: Context): String = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val sims = tm.phoneCount
        "${tm.networkOperatorName.ifBlank { "none" }} (MCC/MNC ${tm.networkOperator.ifBlank { "-" }}, SIM operator ${tm.simOperatorName.ifBlank { "-" }}, modems $sims)"
    } catch (e: Throwable) {
        "error: ${e.javaClass.simpleName}"
    }

    /**
     * No unprivileged public API reports VoLTE directly. Tries ImsMmTelManager (needs privileged
     * permissions on most builds) and always adds the data network type and the Samsung property hints.
     */
    @android.annotation.SuppressLint("MissingPermission", "NewApi")
    private fun volte(context: Context): String {
        val parts = mutableListOf<String>()
        try {
            check(Build.VERSION.SDK_INT >= 30) { "needs API 30" }
            val subId = SubscriptionManager.getDefaultVoiceSubscriptionId()
            val ims = context.getSystemService(android.telephony.ims.ImsManager::class.java)
                .getImsMmTelManager(subId)
            val enabled = ims.isAdvancedCallingSettingEnabled
            parts += "ImsMmTelManager.isAdvancedCallingSettingEnabled=$enabled"
        } catch (e: Throwable) {
            parts += "ImsMmTelManager unavailable (${e.javaClass.simpleName})"
        }
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val granted = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                @Suppress("MissingPermission")
                parts += "dataNetworkType=${tm.dataNetworkType} (13=LTE, 20=NR)"
            } else {
                parts += "network type: grant READ_PHONE_STATE and re-run"
            }
        } catch (e: Throwable) {
            parts += "network type error (${e.javaClass.simpleName})"
        }
        sysProp("persist.sys.ims.volte")?.let { parts += "persist.sys.ims.volte=$it" }
        parts += "confirm manually in Settings > Connections > Mobile networks"
        return parts.joinToString("; ")
    }

    private fun defaultDialer(context: Context): String = try {
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        tm.defaultDialerPackage ?: "none"
    } catch (e: Throwable) {
        "error: ${e.javaClass.simpleName}"
    }

    /**
     * The Samsung call-recording settings activity name is not public. Reports (a) candidate
     * intents that resolve and (b) any exported or unexported activity in the Samsung dialer
     * packages whose name contains "record".
     */
    private fun recordingSettings(context: Context): String {
        val pm = context.packageManager
        val hits = mutableListOf<String>()
        val candidates = listOf(
            "com.samsung.android.dialer" to "com.samsung.android.dialer.settings.CallRecordingSettingsActivity",
            "com.samsung.android.dialer" to "com.samsung.android.dialer.callrecording.settings.CallRecordingSettingsActivity",
            "com.samsung.android.app.telephonyui" to "com.samsung.android.app.telephonyui.callsettings.ui.CallRecordingSettingsActivity",
        )
        for ((pkg, cls) in candidates) {
            val resolved = try {
                pm.resolveActivity(Intent().setComponent(ComponentName(pkg, cls)), 0) != null
            } catch (e: Throwable) {
                false
            }
            if (resolved) hits += "RESOLVES $pkg/$cls"
        }
        for (pkg in RECORDING_CANDIDATE_PACKAGES) {
            try {
                val info = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                info.activities?.filter { it.name.contains("record", ignoreCase = true) }?.forEach {
                    hits += "activity $pkg/${it.name} exported=${it.exported}"
                }
            } catch (e: PackageManager.NameNotFoundException) {
                hits += "package $pkg not installed"
            } catch (e: Throwable) {
                hits += "package $pkg error ${e.javaClass.simpleName}"
            }
        }
        val anyActivity = hits.any { it.startsWith("RESOLVES") || it.startsWith("activity ") }
        return (if (anyActivity) "FOUND" else "NOT FOUND") + (if (hits.isEmpty()) "" else ": " + hits.joinToString("; "))
    }
}
