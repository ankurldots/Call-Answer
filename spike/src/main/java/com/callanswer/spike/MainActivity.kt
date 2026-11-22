package com.callanswer.spike

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Spike menu: setup buttons (permissions, roles, settings), log export, and one entry per check. */
class MainActivity : Activity() {

    private val runtimePermissions = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.SEND_SMS,
    )

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpikeLog.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(header("Setup"))
        root.addView(button("Request runtime permissions") { requestPermissions(runtimePermissions, REQ_PERMS) })
        root.addView(button("Request call screening role") { requestScreeningRole() })
        root.addView(button("Full-screen intent access (Android 14+)") { openFullScreenIntentSettings() })
        root.addView(button("Battery: set to Unrestricted") { openBatterySettings() })
        S1Settings.addSelector(this, root) { refreshStatus() }
        root.addView(button("Export log") { exportLog() })
        root.addView(button("Clear log") { SpikeLog.clear(); refreshStatus() })
        status = TextView(this).apply { textSize = 13f }
        root.addView(status)

        root.addView(header("Checks"))
        SpikeCheck.values().forEach { check ->
            root.addView(button("${check.id}  ${check.title}") {
                startActivity(Intent(this, CheckActivity::class.java).putExtra(CheckActivity.EXTRA_CHECK, check.id))
            })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            SpikeLog.log("S0", "permission $p granted=${grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED}")
        }
        refreshStatus()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE) {
            SpikeLog.log("S0", "call screening role result=${if (resultCode == RESULT_OK) "granted" else "denied/cancelled"}")
            refreshStatus()
        }
    }

    private fun requestScreeningRole() {
        val rm = getSystemService(RoleManager::class.java)
        when {
            !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) ->
                Toast.makeText(this, "Call screening role not available", Toast.LENGTH_LONG).show()
            rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) ->
                Toast.makeText(this, "Role already held", Toast.LENGTH_SHORT).show()
            else -> startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING), REQ_ROLE)
        }
    }

    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT >= 34) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))
            )
        } else {
            Toast.makeText(this, "Not needed below Android 14", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBatterySettings() {
        // Settings screen for the battery optimization list; needs no extra permission.
        // On One UI also set Battery > Unrestricted in App info.
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun exportLog() {
        val intent = SpikeLog.exportIntent(this)
        if (intent == null) Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show() else startActivity(intent)
    }

    private fun refreshStatus() {
        val rm = getSystemService(RoleManager::class.java)
        val perms = runtimePermissions.joinToString("\n") {
            val ok = checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            "${if (ok) "OK  " else "NO  "}${it.substringAfterLast('.')}"
        }
        val role = rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
        val fsi = if (Build.VERSION.SDK_INT >= 34) {
            getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
        } else true
        val battery = getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        status.text = "S1 auto behaviour: ${S1Settings.describe(this)}\n$perms\n${flag(role)}call screening role\n" +
            "${flag(fsi)}full-screen intent allowed\n${flag(battery)}battery unrestricted"
    }

    private fun flag(ok: Boolean) = if (ok) "OK  " else "NO  "

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 24, 0, 8)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { _: View -> onClick() }
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_ROLE = 2
    }
}
